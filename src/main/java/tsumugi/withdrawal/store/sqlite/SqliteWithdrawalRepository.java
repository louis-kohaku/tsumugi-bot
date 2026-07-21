package tsumugi.withdrawal.store.sqlite;

import tsumugi.memory.store.sqlite.SqliteConnectionFactory;
import tsumugi.withdrawal.WithdrawalDataChoice;
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
 * 既存スキーマ（記憶層・initial_setup）とは独立したテーブルのため、
 * このクラス自身でマイグレーションまで面倒を見る。
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
                    channel_id INTEGER,
                    data_choice TEXT,
                    requested_at TEXT NOT NULL,
                    confirmed_at TEXT,
                    data_deleted_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, guild_id)
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_withdrawal_state
                ON withdrawal(state);
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
                (user_id, guild_id, state, channel_id, data_choice,
                 requested_at, confirmed_at, data_deleted_at, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(user_id, guild_id) DO UPDATE SET
                state=excluded.state,
                channel_id=excluded.channel_id,
                data_choice=excluded.data_choice,
                requested_at=excluded.requested_at,
                confirmed_at=excluded.confirmed_at,
                data_deleted_at=excluded.data_deleted_at,
                updated_at=excluded.updated_at
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, record.userId);
                ps.setLong(2, record.guildId);
                ps.setString(3, record.state.name());
                setNullableLong(ps, 4, record.channelId);
                ps.setString(5, record.dataChoice != null ? record.dataChoice.name() : null);
                ps.setString(6, record.requestedAt != null ? record.requestedAt.toString() : Instant.now().toString());
                ps.setString(7, record.confirmedAt != null ? record.confirmedAt.toString() : null);
                ps.setString(8, record.dataDeletedAt != null ? record.dataDeletedAt.toString() : null);
                ps.setString(9, record.createdAt != null ? record.createdAt.toString() : Instant.now().toString());
                ps.setString(10, record.updatedAt.toString());
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
    public List<WithdrawalRecord> loadPendingDeletions() {
        String sql = """
            SELECT * FROM withdrawal
            WHERE state=? AND data_choice=? AND data_deleted_at IS NULL
            """;
        List<WithdrawalRecord> results = new ArrayList<>();
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, WithdrawalState.CONFIRMED.name());
                ps.setString(2, WithdrawalDataChoice.DELETE.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.warning("削除待ちWithdrawalRecordの読み込みに失敗しました: " + e.getMessage());
        }
        return results;
    }

    private WithdrawalRecord map(ResultSet rs) throws SQLException {
        WithdrawalRecord r = new WithdrawalRecord();
        r.userId = rs.getLong("user_id");
        r.guildId = rs.getLong("guild_id");
        r.state = WithdrawalState.valueOf(rs.getString("state"));
        r.channelId = getNullableLong(rs, "channel_id");
        String choice = rs.getString("data_choice");
        r.dataChoice = choice != null ? WithdrawalDataChoice.valueOf(choice) : null;
        r.requestedAt = Instant.parse(rs.getString("requested_at"));
        String confirmedAt = rs.getString("confirmed_at");
        r.confirmedAt = confirmedAt != null ? Instant.parse(confirmedAt) : null;
        String dataDeletedAt = rs.getString("data_deleted_at");
        r.dataDeletedAt = dataDeletedAt != null ? Instant.parse(dataDeletedAt) : null;
        r.createdAt = Instant.parse(rs.getString("created_at"));
        r.updatedAt = Instant.parse(rs.getString("updated_at"));
        return r;
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
