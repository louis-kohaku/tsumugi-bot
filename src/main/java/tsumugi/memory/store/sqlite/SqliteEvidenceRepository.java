package tsumugi.memory.store.sqlite;

import com.google.gson.Gson;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.EvidenceCategory;
import tsumugi.core.model.TsumugiModel.EvidenceStatus;
import tsumugi.core.model.TsumugiModel.Polarity;
import tsumugi.memory.store.EvidenceRepository;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Evidenceをユーザーごとの個別SQLiteファイルに保存するリポジトリ。
 * どのユーザーのDBファイルを使うかはUserConnectionFactoryRegistryが解決する。
 *
 * evidence（通常テーブル）は従来通りON CONFLICT DO UPDATEでUPSERTするが、
 * evidence_vec（sqlite-vecの仮想テーブル）はUPSERTをサポートしないため、
 * DELETE→INSERTで代替する。
 */
public final class SqliteEvidenceRepository implements EvidenceRepository {

    private static final Logger logger = Logger.getLogger(SqliteEvidenceRepository.class.getName());
    private static final Gson GSON = new Gson();

    private final UserConnectionFactoryRegistry registry;

    public SqliteEvidenceRepository(UserConnectionFactoryRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean isVecAvailable(long userId) {
        SqliteConnectionFactory factory = registry.forUser(userId);
        return factory.isVecAvailable();
    }

    @Override
    public void save(Evidence e) {
        SqliteConnectionFactory factory = registry.forUser(e.userId);
        try (Connection conn = factory.open()) {
            conn.setAutoCommit(false);

            // ── 通常テーブル(evidence)側: ここはBツリーテーブルなのでUPSERTが使える ──
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO evidence
                        (id, user_id, category, topic, content, confidence, polarity,
                         source_event_ids, extracted_at, supersedes, status)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        confidence=excluded.confidence,
                        polarity=excluded.polarity,
                        source_event_ids=excluded.source_event_ids,
                        extracted_at=excluded.extracted_at,
                        supersedes=excluded.supersedes,
                        status=excluded.status
                    """)) {
                ps.setString(1, e.id);
                ps.setLong(2, e.userId);
                ps.setString(3, e.category.name());
                ps.setString(4, e.topic);
                ps.setString(5, e.content);
                ps.setDouble(6, e.confidence);
                ps.setString(7, e.polarity.name());
                ps.setString(8, GSON.toJson(e.sourceEventIds));
                ps.setString(9, e.extractedAt.toString());
                ps.setString(10, e.supersedes);
                ps.setString(11, e.status.name());
                ps.executeUpdate();
            }

            // ── 仮想テーブル(evidence_vec)側: UPSERT不可のためDELETE→INSERTで代替 ──
            if (e.embedding != null && factory.isVecAvailable()) {
                upsertEmbedding(conn, e.id, e.embedding);
            }

            conn.commit();
        } catch (SQLException ex) {
            logger.warning("Evidenceの保存に失敗しました (userId=" + e.userId + "): " + ex.getMessage());
        }
    }

    /**
     * sqlite-vecの仮想テーブル(vec0)はON CONFLICT/UPSERTをサポートしないため、
     * 既存行を明示的にDELETEしてからINSERTすることで「更新」を表現する。
     * 呼び出し元で既にトランザクション内にいる前提。
     */
    private void upsertEmbedding(Connection conn, String evidenceId, float[] embedding) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement(
                "DELETE FROM evidence_vec WHERE evidence_id = ?")) {
            del.setString(1, evidenceId);
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO evidence_vec (evidence_id, embedding) VALUES (?, ?)")) {
            ins.setString(1, evidenceId);
            ins.setBytes(2, toBytes(embedding));
            ins.executeUpdate();
        }
    }

    @Override
    public List<Evidence> loadActive(long userId, EvidenceCategory category) {
        String sql = "SELECT * FROM evidence WHERE user_id=? AND category=? AND status='ACTIVE'";
        return query(userId, sql, category.name());
    }

    @Override
    public List<Evidence> loadActive(long userId) {
        String sql = "SELECT * FROM evidence WHERE user_id=? AND status='ACTIVE'";
        return query(userId, sql, null);
    }

    @Override
    public List<Evidence> loadAll(long userId) {
        String sql = "SELECT * FROM evidence WHERE user_id=?";
        return query(userId, sql, null);
    }

    @Override
    public void deleteById(long userId, String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) return;
        SqliteConnectionFactory factory = registry.forUser(userId);
        try (Connection conn = factory.open()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM evidence WHERE id=?")) {
                ps.setString(1, evidenceId);
                ps.executeUpdate();
            }
            if (factory.isVecAvailable()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM evidence_vec WHERE evidence_id=?")) {
                    ps.setString(1, evidenceId);
                    ps.executeUpdate();
                }
            }
            conn.commit();
        } catch (SQLException ex) {
            logger.warning("Evidence単体削除に失敗しました (userId=" + userId + ", id=" + evidenceId + "): " + ex.getMessage());
        }
    }

    @Override
    public int deleteAll(long userId) {
        SqliteConnectionFactory factory = registry.forUser(userId);
        try (Connection conn = factory.open()) {
            conn.setAutoCommit(false);

            int deleted;
            if (factory.isVecAvailable()) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        DELETE FROM evidence_vec
                        WHERE evidence_id IN (SELECT id FROM evidence WHERE user_id=?)
                        """)) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM evidence WHERE user_id=?")) {
                ps.setLong(1, userId);
                deleted = ps.executeUpdate();
            }
            conn.commit();
            return deleted;
        } catch (SQLException ex) {
            logger.warning("Evidence全削除に失敗しました (userId=" + userId + "): " + ex.getMessage());
            return 0;
        }
    }

    @Override
    public List<Evidence> searchByVector(long userId, float[] queryEmbedding, int topK) {
        SqliteConnectionFactory factory = registry.forUser(userId);
        if (!factory.isVecAvailable()) return new ArrayList<>();

        String sql = """
            SELECT e.*
            FROM evidence_vec v
            JOIN evidence e ON e.id = v.evidence_id
            WHERE v.embedding MATCH ?
              AND k = ?
              AND e.user_id = ?
              AND e.status = 'ACTIVE'
            ORDER BY v.distance
            """;
        try (Connection conn = factory.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, toBytes(queryEmbedding));
            ps.setInt(2, topK);
            ps.setLong(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException ex) {
            logger.warning("ベクトル検索に失敗しました (userId=" + userId + "): " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Evidence> query(long userId, String sql, String categoryOrNull) {
        SqliteConnectionFactory factory = registry.forUser(userId);
        try (Connection conn = factory.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            if (categoryOrNull != null) ps.setString(2, categoryOrNull);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException ex) {
            logger.warning("Evidenceの読み込みに失敗しました (userId=" + userId + "): " + ex.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Evidence> mapAll(ResultSet rs) throws SQLException {
        List<Evidence> results = new ArrayList<>();
        while (rs.next()) {
            Evidence e = new Evidence();
            e.id = rs.getString("id");
            e.userId = rs.getLong("user_id");
            e.category = EvidenceCategory.valueOf(rs.getString("category"));
            e.topic = rs.getString("topic");
            e.content = rs.getString("content");
            e.confidence = rs.getDouble("confidence");
            e.polarity = Polarity.valueOf(rs.getString("polarity"));
            e.sourceEventIds = GSON.fromJson(rs.getString("source_event_ids"),
                    new com.google.gson.reflect.TypeToken<List<String>>(){}.getType());
            e.extractedAt = Instant.parse(rs.getString("extracted_at"));
            e.supersedes = rs.getString("supersedes");
            e.status = EvidenceStatus.valueOf(rs.getString("status"));
            results.add(e);
        }
        return results;
    }

    private byte[] toBytes(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vec) buf.putFloat(f);
        return buf.array();
    }
}
