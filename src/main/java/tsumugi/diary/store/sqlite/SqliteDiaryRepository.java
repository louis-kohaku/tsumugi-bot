package tsumugi.diary.store.sqlite;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import tsumugi.diary.DiaryMode;
import tsumugi.diary.model.DiaryRecord;
import tsumugi.diary.store.DiaryRepository;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * DiaryRecordをdiary_recordsテーブルに保存する実装。
 * 既存スキーマ（記憶層・initial_setup・withdrawal）とは独立したテーブルのため、
 * このクラス自身でマイグレーションまで面倒を見る（既存Repository実装群と同じ方針）。
 */
public final class SqliteDiaryRepository implements DiaryRepository {

    private static final Logger logger = Logger.getLogger(SqliteDiaryRepository.class.getName());
    private static final Gson GSON = new Gson();

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteDiaryRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS diary_records (
                    id TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    date TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    wake_up_time TEXT,
                    timeline_json TEXT NOT NULL,
                    achievements TEXT,
                    bad_points TEXT,
                    tomorrow_challenge TEXT,
                    daily_summary TEXT,
                    created_at TEXT NOT NULL
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_diary_user_date
                ON diary_records(user_id, date);
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public void save(DiaryRecord record) {
        String sql = """
            INSERT INTO diary_records
                (id, user_id, date, mode, wake_up_time, timeline_json,
                 achievements, bad_points, tomorrow_challenge, daily_summary, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
                wake_up_time=excluded.wake_up_time,
                timeline_json=excluded.timeline_json,
                achievements=excluded.achievements,
                bad_points=excluded.bad_points,
                tomorrow_challenge=excluded.tomorrow_challenge,
                daily_summary=excluded.daily_summary
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, record.id);
                ps.setLong(2, record.userId);
                ps.setString(3, record.date.toString());
                ps.setString(4, record.mode.name());
                ps.setString(5, record.wakeUpTime);
                ps.setString(6, GSON.toJson(record.timeline));
                ps.setString(7, record.achievements);
                ps.setString(8, record.badPoints);
                ps.setString(9, record.tomorrowChallenge);
                ps.setString(10, record.dailySummary);
                ps.setString(11, record.createdAt != null ? record.createdAt.toString() : Instant.now().toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("DiaryRecordの保存に失敗しました (userId=" + record.userId + "): " + e.getMessage());
        }
    }

    @Override
    public List<DiaryRecord> loadByUser(long userId) {
        String sql = "SELECT * FROM diary_records WHERE user_id=? ORDER BY date ASC";
        List<DiaryRecord> results = new ArrayList<>();
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) results.add(map(rs));
                }
            }
        } catch (SQLException e) {
            logger.warning("DiaryRecordの読み込みに失敗しました (userId=" + userId + "): " + e.getMessage());
        }
        return results;
    }

    private DiaryRecord map(ResultSet rs) throws SQLException {
        DiaryRecord r = new DiaryRecord();
        r.id = rs.getString("id");
        r.userId = rs.getLong("user_id");
        r.date = LocalDate.parse(rs.getString("date"));
        r.mode = DiaryMode.valueOf(rs.getString("mode"));
        r.wakeUpTime = rs.getString("wake_up_time");
        Map<String, String> timeline = GSON.fromJson(rs.getString("timeline_json"),
                new TypeToken<LinkedHashMap<String, String>>() {}.getType());
        r.timeline = timeline != null ? timeline : new LinkedHashMap<>();
        r.achievements = rs.getString("achievements");
        r.badPoints = rs.getString("bad_points");
        r.tomorrowChallenge = rs.getString("tomorrow_challenge");
        r.dailySummary = rs.getString("daily_summary");
        r.createdAt = Instant.parse(rs.getString("created_at"));
        return r;
    }
}
