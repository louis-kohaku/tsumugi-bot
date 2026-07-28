package tsumugi.broadcast.store.sqlite;

import tsumugi.broadcast.model.BroadcastHistoryEntry;
import tsumugi.broadcast.store.BroadcastRepository;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * BroadcastHistoryEntryをbroadcast_historyテーブルに保存する実装。
 * 既存スキーマとは独立したテーブルのため、このクラス自身でマイグレーションまで面倒を見る
 * （既存Repository実装群と同じ方針）。ログ目的のため保存のみを提供する。
 */
public final class SqliteBroadcastRepository implements BroadcastRepository {

    private static final Logger logger = Logger.getLogger(SqliteBroadcastRepository.class.getName());

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteBroadcastRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS broadcast_history (
                    id TEXT PRIMARY KEY,
                    guild_id INTEGER NOT NULL,
                    original_content TEXT NOT NULL,
                    final_content TEXT NOT NULL,
                    success_count INTEGER NOT NULL,
                    failure_count INTEGER NOT NULL,
                    broadcast_at TEXT NOT NULL
                );
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public void save(BroadcastHistoryEntry entry) {
        String sql = """
            INSERT INTO broadcast_history
                (id, guild_id, original_content, final_content, success_count, failure_count, broadcast_at)
            VALUES (?,?,?,?,?,?,?)
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, entry.id);
                ps.setLong(2, entry.guildId);
                ps.setString(3, entry.originalContent);
                ps.setString(4, entry.finalContent);
                ps.setInt(5, entry.successCount);
                ps.setInt(6, entry.failureCount);
                ps.setString(7, (entry.broadcastAt != null ? entry.broadcastAt : Instant.now()).toString());
                ps.executeUpdate();
            }
            logger.info("配信履歴を保存しました: guildId=" + entry.guildId
                    + " success=" + entry.successCount + " failure=" + entry.failureCount);
        } catch (SQLException e) {
            logger.warning("配信履歴の保存に失敗しました (guildId=" + entry.guildId + "): " + e.getMessage());
        }
    }
}
