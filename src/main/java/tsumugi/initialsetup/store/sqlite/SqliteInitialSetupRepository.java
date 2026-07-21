package tsumugi.initialsetup.store.sqlite;

import tsumugi.initialsetup.InitialSetupState;
import tsumugi.initialsetup.model.InitialSetupRecord;
import tsumugi.initialsetup.store.InitialSetupRepository;
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
 * InitialSetupRecordをinitial_setupテーブルに保存する実装。
 *
 * 既存のSqliteSchema（記憶層）とはテーブルが独立しているため、
 * 既存スキーマを変更せずに済むよう、このクラス自身でテーブルの
 * 存在確認・作成（マイグレーション）まで面倒を見る。
 * SqliteConnectionFactoryはコネクション取得のためだけに再利用する。
 */
public final class SqliteInitialSetupRepository implements InitialSetupRepository {

    private static final Logger logger = Logger.getLogger(SqliteInitialSetupRepository.class.getName());

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteInitialSetupRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS initial_setup (
                    user_id INTEGER NOT NULL,
                    guild_id INTEGER NOT NULL,
                    state TEXT NOT NULL,
                    display_name TEXT,
                    setup_channel_id INTEGER,
                    garden_category_id INTEGER,
                    chat_channel_id INTEGER,
                    log_channel_id INTEGER,
                    announce_channel_id INTEGER,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, guild_id)
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_initial_setup_state
                ON initial_setup(state);
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public InitialSetupRecord load(long userId, long guildId) {
        String sql = "SELECT * FROM initial_setup WHERE user_id=? AND guild_id=?";
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setLong(2, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return map(rs);
                    return new InitialSetupRecord(userId, guildId);
                }
            }
        } catch (SQLException e) {
            logger.warning("InitialSetupRecordの読み込みに失敗しました (userId=" + userId + "): " + e.getMessage());
            return new InitialSetupRecord(userId, guildId);
        }
    }

    @Override
    public void save(InitialSetupRecord record) {
        record.touch();
        String sql = """
            INSERT INTO initial_setup
                (user_id, guild_id, state, display_name, setup_channel_id,
                 garden_category_id, chat_channel_id, log_channel_id, announce_channel_id,
                 created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(user_id, guild_id) DO UPDATE SET
                state=excluded.state,
                display_name=excluded.display_name,
                setup_channel_id=excluded.setup_channel_id,
                garden_category_id=excluded.garden_category_id,
                chat_channel_id=excluded.chat_channel_id,
                log_channel_id=excluded.log_channel_id,
                announce_channel_id=excluded.announce_channel_id,
                updated_at=excluded.updated_at
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, record.userId);
                ps.setLong(2, record.guildId);
                ps.setString(3, record.state.name());
                ps.setString(4, record.displayName);
                setNullableLong(ps, 5, record.setupChannelId);
                setNullableLong(ps, 6, record.gardenCategoryId);
                setNullableLong(ps, 7, record.chatChannelId);
                setNullableLong(ps, 8, record.logChannelId);
                setNullableLong(ps, 9, record.announceChannelId);
                ps.setString(10, record.createdAt != null ? record.createdAt.toString() : Instant.now().toString());
                ps.setString(11, record.updatedAt.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("InitialSetupRecordの保存に失敗しました (userId=" + record.userId + "): " + e.getMessage());
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
        String sql = "DELETE FROM initial_setup WHERE user_id=? AND guild_id=?";
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setLong(2, guildId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("InitialSetupRecordの削除に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
    }

    @Override
    public List<InitialSetupRecord> loadByState(InitialSetupState state) {
        String sql = "SELECT * FROM initial_setup WHERE state=?";
        List<InitialSetupRecord> results = new ArrayList<>();
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, state.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.warning("状態指定でのInitialSetupRecord読み込みに失敗しました (state=" + state + "): " + e.getMessage());
        }
        return results;
    }

    private InitialSetupRecord map(ResultSet rs) throws SQLException {
        InitialSetupRecord r = new InitialSetupRecord();
        r.userId = rs.getLong("user_id");
        r.guildId = rs.getLong("guild_id");
        r.state = InitialSetupState.valueOf(rs.getString("state"));
        r.displayName = rs.getString("display_name");
        r.setupChannelId = getNullableLong(rs, "setup_channel_id");
        r.gardenCategoryId = getNullableLong(rs, "garden_category_id");
        r.chatChannelId = getNullableLong(rs, "chat_channel_id");
        r.logChannelId = getNullableLong(rs, "log_channel_id");
        r.announceChannelId = getNullableLong(rs, "announce_channel_id");
        r.createdAt = Instant.parse(rs.getString("created_at"));
        r.updatedAt = Instant.parse(rs.getString("updated_at"));
        return r;
    }

    private Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
