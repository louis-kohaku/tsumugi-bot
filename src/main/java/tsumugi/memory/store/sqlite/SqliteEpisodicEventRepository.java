package tsumugi.memory.store.sqlite;

import tsumugi.core.model.TsumugiModel.ChannelType;
import tsumugi.core.model.TsumugiModel.EpisodicEvent;
import tsumugi.core.model.TsumugiModel.Speaker;
import tsumugi.memory.store.EpisodicEventRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * EpisodicEventをユーザーごとに分離されたSQLiteファイル（{userDbDir}/{userId}/tsumugi.db）に保存する。
 * occurred_atはEpisodicEventのコンストラクタでInstant.now()が設定される前提だが、
 * 念のためnullの場合はInstant.now()で補完する。
 */
public final class SqliteEpisodicEventRepository implements EpisodicEventRepository {

    private static final Logger logger = Logger.getLogger(SqliteEpisodicEventRepository.class.getName());

    private final UserConnectionFactoryRegistry registry;

    public SqliteEpisodicEventRepository(UserConnectionFactoryRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void save(EpisodicEvent event) {
        String sql = """
            INSERT INTO episodic_events
                (id, user_id, occurred_at, channel_type, raw_text, speaker, logical_date, linked_session_id)
            VALUES (?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                raw_text=excluded.raw_text
            """;
        try (Connection conn = registry.forUser(event.userId).open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Instant occurredAt = event.occurredAt != null ? event.occurredAt : Instant.now();
            ps.setString(1, event.id);
            ps.setLong(2, event.userId);
            ps.setString(3, occurredAt.toString());
            ps.setString(4, event.channelType.name());
            ps.setString(5, event.rawText);
            ps.setString(6, event.speaker.name());
            ps.setString(7, event.logicalDate.toString());
            ps.setString(8, event.linkedSessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("EpisodicEventの保存に失敗しました (userId=" + event.userId + "): " + e.getMessage());
        }
    }

    @Override
    public List<EpisodicEvent> loadRecent(long userId, int limit) {
        String sql = """
            SELECT * FROM episodic_events
            WHERE user_id = ?
            ORDER BY occurred_at DESC
            LIMIT ?
            """;
        try (Connection conn = registry.forUser(userId).open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<EpisodicEvent> results = mapAll(rs);
                // DESCで取ったのを時系列昇順に戻す（会話履歴として自然な順序にするため）
                java.util.Collections.reverse(results);
                return results;
            }
        } catch (SQLException e) {
            logger.warning("EpisodicEventの読み込みに失敗しました (userId=" + userId + "): " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<EpisodicEvent> loadAll(long userId) {
        String sql = """
            SELECT * FROM episodic_events
            WHERE user_id = ?
            ORDER BY occurred_at ASC
            """;
        try (Connection conn = registry.forUser(userId).open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            logger.warning("EpisodicEventの全件読み込みに失敗しました (userId=" + userId + "): " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public int deleteAll(long userId) {
        String sql = "DELETE FROM episodic_events WHERE user_id = ?";
        try (Connection conn = registry.forUser(userId).open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("EpisodicEventの削除に失敗しました (userId=" + userId + "): " + e.getMessage());
            return 0;
        }
    }

    private List<EpisodicEvent> mapAll(ResultSet rs) throws SQLException {
        List<EpisodicEvent> results = new ArrayList<>();
        while (rs.next()) {
            EpisodicEvent e = new EpisodicEvent();
            e.id = rs.getString("id");
            e.userId = rs.getLong("user_id");
            e.occurredAt = Instant.parse(rs.getString("occurred_at"));
            e.channelType = ChannelType.valueOf(rs.getString("channel_type"));
            e.rawText = rs.getString("raw_text");
            e.speaker = Speaker.valueOf(rs.getString("speaker"));
            e.logicalDate = LocalDate.parse(rs.getString("logical_date"));
            e.linkedSessionId = rs.getString("linked_session_id");
            results.add(e);
        }
        return results;
    }
}
