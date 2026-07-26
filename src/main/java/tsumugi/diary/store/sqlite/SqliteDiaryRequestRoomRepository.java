package tsumugi.diary.store.sqlite;

import tsumugi.diary.store.DiaryRequestRoomRepository;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * ユーザーごとの要望部屋チャンネルIDをdiary_request_roomsテーブルに保存する実装。
 * 既存スキーマ（記憶層・initial_setup・withdrawal・diary_records等）とは独立した
 * テーブルのため、このクラス自身でマイグレーションまで面倒を見る（既存Repository実装群と同じ方針）。
 */
public final class SqliteDiaryRequestRoomRepository implements DiaryRequestRoomRepository {

    private static final Logger logger = Logger.getLogger(SqliteDiaryRequestRoomRepository.class.getName());

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteDiaryRequestRoomRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS diary_request_rooms (
                    user_id INTEGER PRIMARY KEY,
                    channel_id INTEGER NOT NULL,
                    updated_at TEXT NOT NULL
                );
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public Long loadChannelId(long userId) {
        String sql = "SELECT channel_id FROM diary_request_rooms WHERE user_id=?";
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    long channelId = rs.getLong("channel_id");
                    return rs.wasNull() ? null : channelId;
                }
            }
        } catch (SQLException e) {
            logger.warning("要望部屋チャンネルIDの読み込みに失敗しました (userId=" + userId + "): " + e.getMessage());
            return null;
        }
    }

    @Override
    public void save(long userId, long channelId) {
        String sql = """
            INSERT INTO diary_request_rooms (user_id, channel_id, updated_at)
            VALUES (?,?,?)
            ON CONFLICT(user_id) DO UPDATE SET
                channel_id=excluded.channel_id,
                updated_at=excluded.updated_at
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setLong(2, channelId);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("要望部屋チャンネルIDの保存に失敗しました (userId=" + userId + ", channelId=" + channelId + "): " + e.getMessage());
        }
    }

    @Override
    public void delete(long userId) {
        String sql = "DELETE FROM diary_request_rooms WHERE user_id=?";
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("要望部屋チャンネルIDの削除に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
    }
}
