package tsumugi.forcedwithdrawal.store.sqlite;

import tsumugi.forcedwithdrawal.ForcedWithdrawalState;
import tsumugi.forcedwithdrawal.model.ForcedWithdrawalRecord;
import tsumugi.forcedwithdrawal.store.ForcedWithdrawalRepository;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * ForcedWithdrawalRecordをforced_withdrawalテーブルに保存する実装。
 * 既存スキーマとは独立したテーブルのため、このクラス自身でマイグレーションまで面倒を見る
 * （既存Repository実装群と同じ方針）。
 */
public final class SqliteForcedWithdrawalRepository implements ForcedWithdrawalRepository {

    private static final Logger logger = Logger.getLogger(SqliteForcedWithdrawalRepository.class.getName());

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteForcedWithdrawalRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS forced_withdrawal (
                    id TEXT PRIMARY KEY,
                    target_user_id INTEGER NOT NULL,
                    guild_id INTEGER NOT NULL,
                    admin_user_id INTEGER NOT NULL,
                    target_display_name TEXT,
                    reason TEXT,
                    state TEXT NOT NULL,
                    confirmed_at TEXT,
                    execute_at TEXT,
                    executed_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_forced_withdrawal_state
                ON forced_withdrawal(state);
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public void save(ForcedWithdrawalRecord record) {
        record.touch();
        String sql = """
            INSERT INTO forced_withdrawal
                (id, target_user_id, guild_id, admin_user_id, target_display_name, reason,
                 state, confirmed_at, execute_at, executed_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                state=excluded.state,
                reason=excluded.reason,
                confirmed_at=excluded.confirmed_at,
                execute_at=excluded.execute_at,
                executed_at=excluded.executed_at,
                updated_at=excluded.updated_at
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, record.id);
                ps.setLong(2, record.targetUserId);
                ps.setLong(3, record.guildId);
                ps.setLong(4, record.adminUserId);
                ps.setString(5, record.targetDisplayName);
                ps.setString(6, record.reason);
                ps.setString(7, record.state.name());
                ps.setString(8, record.confirmedAt != null ? record.confirmedAt.toString() : null);
                ps.setString(9, record.executeAt != null ? record.executeAt.toString() : null);
                ps.setString(10, record.executedAt != null ? record.executedAt.toString() : null);
                ps.setString(11, record.createdAt != null ? record.createdAt.toString() : Instant.now().toString());
                ps.setString(12, record.updatedAt.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("ForcedWithdrawalRecordの保存に失敗しました (id=" + record.id + "): " + e.getMessage());
        }
    }

    @Override
    public ForcedWithdrawalRecord loadById(String id) {
        String sql = "SELECT * FROM forced_withdrawal WHERE id=?";
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        } catch (SQLException e) {
            logger.warning("ForcedWithdrawalRecordの読み込みに失敗しました (id=" + id + "): " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<ForcedWithdrawalRecord> loadByState(ForcedWithdrawalState state) {
        String sql = "SELECT * FROM forced_withdrawal WHERE state=?";
        List<ForcedWithdrawalRecord> results = new ArrayList<>();
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, state.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.warning("状態指定でのForcedWithdrawalRecord読み込みに失敗しました (state=" + state + "): " + e.getMessage());
        }
        return results;
    }

    @Override
    public void delete(String id) {
        String sql = "DELETE FROM forced_withdrawal WHERE id=?";
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("ForcedWithdrawalRecordの削除に失敗しました (id=" + id + "): " + e.getMessage());
        }
    }

    private ForcedWithdrawalRecord map(ResultSet rs) throws SQLException {
        ForcedWithdrawalRecord r = new ForcedWithdrawalRecord();
        r.id = rs.getString("id");
        r.targetUserId = rs.getLong("target_user_id");
        r.guildId = rs.getLong("guild_id");
        r.adminUserId = rs.getLong("admin_user_id");
        r.targetDisplayName = rs.getString("target_display_name");
        r.reason = rs.getString("reason");
        r.state = ForcedWithdrawalState.valueOf(rs.getString("state"));
        String confirmedAt = rs.getString("confirmed_at");
        r.confirmedAt = confirmedAt != null ? Instant.parse(confirmedAt) : null;
        String executeAt = rs.getString("execute_at");
        r.executeAt = executeAt != null ? Instant.parse(executeAt) : null;
        String executedAt = rs.getString("executed_at");
        r.executedAt = executedAt != null ? Instant.parse(executedAt) : null;
        r.createdAt = Instant.parse(rs.getString("created_at"));
        r.updatedAt = Instant.parse(rs.getString("updated_at"));
        return r;
    }
}
