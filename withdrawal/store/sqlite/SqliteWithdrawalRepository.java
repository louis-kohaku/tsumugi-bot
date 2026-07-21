package tsumugi.withdrawal.store.sqlite;

import tsumugi.memory.store.sqlite.SqliteConnectionFactory;
import tsumugi.withdrawal.WithdrawalState;
import tsumugi.withdrawal.model.WithdrawalRecord;
import tsumugi.withdrawal.store.WithdrawalRepository;

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
 * WithdrawalRecordをwithdrawalテーブルに保存する実装。
 * SqliteInitialSetupRepositoryと同様、このクラス自身でスキーマの存在確認・作成まで面倒を見る。
 */
public final class SqliteWithdrawalRepository implements WithdrawalRepository {

    private static final Logger logger = Logger.getLogger(SqliteWithdrawalRepository.class.getName());

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteWithdrawalRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS withdrawal (
                    user_id INTEGER NOT NULL,
                    guild_id INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    display_name TEXT,
                    channel_id INTEGER,
                    requested_at TEXT,
                    deadline_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, guild_id)
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_withdrawal_state
                ON withdrawal(state);
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_withdrawal_deadline
                ON withdrawal(deadline_at);
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public WithdrawalRecord load(long userId, long guildId) {
        String sql = "SELECT * FROM withdrawal WHERE user_id=? AND guild_id=?";
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setLong(2, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return map(rs);
                    return new WithdrawalRecord(userId, guildId);
                }
            }
        } catch (SQLException e) {
            logger.warning("WithdrawalRecordの読み込みに失敗しました (userId=" + userId + "): " + e.getMessage());
            return new WithdrawalRecord(userId, guildId);
        }
    }

    @Override
    public void save(WithdrawalRecord record) {
        record.touch();
        String sql = """
            INSERT INTO withdrawal
                (user_id, guild_id, state, display_name, channel_id, requested_at, deadline_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT(user_id, guild_id) DO UPDATE SET
                state=excluded.state,
                display_name=excluded.display_name,
                channel_id=excluded.channel_id,
                requested_at=excluded.requested_at,
                deadline_at=excluded.deadline_at,
                updated_at=excluded.updated_at
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, record.userId);
                ps.setLong(2, record.guildId);
                ps.setString(3, record.state.name());
                ps.setString(4, record.displayName);
                setNullableLong(ps, 5, record.channelId);
                ps.setString(6, record.requestedAt != null ? record.requestedAt.toString() : null);
                ps.setString(7, record.deadlineAt != null ? record.deadlineAt.toString() : null);
                ps.setString(8, record.createdAt != null ? record.createdAt.toString() : Instant.now().toString());
                ps.setString(9, record.updatedAt.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("WithdrawalRecordの保存に失敗しました (userId=" + record.userId + "): " + e.getMessage());
        }
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) {
            ps.setLong(index, value);
        } else {
            ps.setNull(index, java.sql.Types.BIGINT);
        }
    }

    @Override
    public void delete(long userId, long guildId) {
        String sql = "DELETE FROM withdrawal WHERE user_id=? AND guild_id=?";
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setLong(2, guildId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("WithdrawalRecordの削除に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
    }

    @Override
    public List<WithdrawalRecord> loadByState(WithdrawalState state) {
        String sql = "SELECT * FROM withdrawal WHERE state=?";
        List<WithdrawalRecord> results = new ArrayList<>();
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, state.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.warning("状態指定でのWithdrawalRecord読み込みに失敗しました (state=" + state + "): " + e.getMessage());
        }
        return results;
    }

    @Override
    public List<WithdrawalRecord> loadExpired(Instant now) {
        String sql = """
            SELECT * FROM withdrawal
            WHERE state IN ('WAITING_CHOICE','PENDING_ADMIN_REVIEW')
              AND deadline_at IS NOT NULL
              AND deadline_at <= ?
            """;
        List<WithdrawalRecord> results = new ArrayList<>();
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, now.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.warning("期限切れWithdrawalRecordの読み込みに失敗しました: " + e.getMessage());
        }
        return results;
    }

    private WithdrawalRecord map(ResultSet rs) throws SQLException {
        WithdrawalRecord r = new WithdrawalRecord();
        r.userId = rs.getLong("user_id");
        r.guildId = rs.getLong("guild_id");
        r.state = WithdrawalState.valueOf(rs.getString("state"));
        r.displayName = rs.getString("display_name");
        r.channelId = getNullableLong(rs, "channel_id");
        String requestedAt = rs.getString("requested_at");
        r.requestedAt = requestedAt != null ? Instant.parse(requestedAt) : null;
        String deadlineAt = rs.getString("deadline_at");
        r.deadlineAt = deadlineAt != null ? Instant.parse(deadlineAt) : null;
        r.createdAt = Instant.parse(rs.getString("created_at"));
        r.updatedAt = Instant.parse(rs.getString("updated_at"));
        return r;
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
