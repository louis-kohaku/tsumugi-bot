package tsumugi.memory.anonymized;

import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * AnonymizedEvidenceをanonymized_evidenceテーブルに保存する。
 * 既存のSqliteSchemaとは独立したテーブルのため、このクラス自身でマイグレーションを面倒見る。
 *
 * 重要: user_id・source_event_ids・元のEvidence idは一切カラムに含めない。
 * 突合不能な形で保存することが本クラスの存在意義。
 */
public final class SqliteAnonymizedDataRepository implements AnonymizedDataRepository {

    private static final Logger logger = Logger.getLogger(SqliteAnonymizedDataRepository.class.getName());

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteAnonymizedDataRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS anonymized_evidence (
                    id TEXT PRIMARY KEY,
                    category TEXT NOT NULL,
                    topic TEXT,
                    content TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    polarity TEXT NOT NULL,
                    anonymized_at TEXT NOT NULL
                );
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public void saveAnonymized(List<Evidence> evidences) {
        if (evidences == null || evidences.isEmpty()) return;

        String sql = """
            INSERT INTO anonymized_evidence (id, category, topic, content, confidence, polarity, anonymized_at)
            VALUES (?,?,?,?,?,?,?)
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Evidence e : evidences) {
                    AnonymizedEvidence a = toAnonymized(e);
                    ps.setString(1, a.id);
                    ps.setString(2, a.category.name());
                    ps.setString(3, a.topic);
                    ps.setString(4, a.content);
                    ps.setDouble(5, a.confidence);
                    ps.setString(6, a.polarity.name());
                    ps.setString(7, a.anonymizedAt.toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            logger.info("匿名データを保存しました: 件数=" + evidences.size());
        } catch (SQLException e) {
            logger.warning("匿名データの保存に失敗しました: " + e.getMessage());
        }
    }

    /** 個人特定情報（userId, sourceEventIds, 元id等）を破棄し、傾向データのみを新規idで包み直す。 */
    private AnonymizedEvidence toAnonymized(Evidence e) {
        AnonymizedEvidence a = new AnonymizedEvidence();
        a.category = e.category;
        a.topic = e.topic;
        a.content = e.content;
        a.confidence = e.confidence;
        a.polarity = e.polarity;
        return a;
    }
}
