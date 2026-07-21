package tsumugi.membership.store.sqlite;

import tsumugi.membership.MembershipEvent;
import tsumugi.membership.MembershipEvent.EventType;
import tsumugi.membership.store.MembershipRepository;
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
 * MembershipEventをmembership_eventsテーブルに保存する。
 * 追記型ログのみで、更新・削除は行わない（削除権対応が必要になった場合は
 * DataSubjectRightsService側にdeleteAll(userId)相当を追加する）。
 *
 * 既存のSqliteSchema（記憶層）とはテーブルが独立しているため、
 * このクラス自身でテーブルの存在確認・作成まで面倒を見る
 * （SqliteInitialSetupRepositoryと同じ方針）。
 */
public final class SqliteMembershipRepository implements MembershipRepository {

    private static final Logger logger = Logger.getLogger(SqliteMembershipRepository.class.getName());

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteMembershipRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS membership_events (
                    id TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    event_type TEXT NOT NULL,
                    occurred_at TEXT NOT NULL,
                    via_withdrawal INTEGER NOT NULL
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_membership_events_user_occurred
                ON membership_events(user_id, occurred_at);
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public void save(MembershipEvent event) {
        String sql = """
            INSERT INTO membership_events (id, user_id, event_type, occurred_at, via_withdrawal)
            VALUES (?,?,?,?,?)
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, event.id);
                ps.setLong(2, event.userId);
                ps.setString(3, event.eventType.name());
                ps.setString(4, event.occurredAt.toString());
                ps.setInt(5, event.viaWithdrawal ? 1 : 0);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("MembershipEventの保存に失敗しました (userId=" + event.userId + "): " + e.getMessage());
        }
    }

    @Override
    public MembershipEvent loadLatest(long userId) {
        String sql = """
            SELECT * FROM membership_events
            WHERE user_id = ?
            ORDER BY occurred_at DESC
            LIMIT 1
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        } catch (SQLException e) {
            logger.warning("MembershipEventの最新取得に失敗しました (userId=" + userId + "): " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<MembershipEvent> loadHistory(long userId) {
        String sql = """
            SELECT * FROM membership_events
            WHERE user_id = ?
            ORDER BY occurred_at ASC
            """;
        List<MembershipEvent> results = new ArrayList<>();
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.warning("MembershipEvent履歴の取得に失敗しました (userId=" + userId + "): " + e.getMessage());
        }
        return results;
    }

    private MembershipEvent map(ResultSet rs) throws SQLException {
        MembershipEvent e = new MembershipEvent();
        e.id = rs.getString("id");
        e.userId = rs.getLong("user_id");
        e.eventType = EventType.valueOf(rs.getString("event_type"));
        e.occurredAt = Instant.parse(rs.getString("occurred_at"));
        e.viaWithdrawal = rs.getInt("via_withdrawal") != 0;
        return e;
    }
}
