package tsumugi.diary.store.sqlite;

import tsumugi.diary.DiaryMode;
import tsumugi.diary.DiaryQueueStatus;
import tsumugi.diary.model.DiaryQueueEntry;
import tsumugi.diary.store.DiaryQueueRepository;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * DiaryQueueEntryをdiary_queueテーブルに保存する実装。
 * 既存スキーマ（記憶層・initial_setup・withdrawal・diary_records）とは独立したテーブルのため、
 * このクラス自身でマイグレーションまで面倒を見る（既存Repository実装群と同じ方針）。
 */
public final class SqliteDiaryQueueRepository implements DiaryQueueRepository {

    private static final Logger logger = Logger.getLogger(SqliteDiaryQueueRepository.class.getName());
    private static final Gson GSON = new Gson();

    private final SqliteConnectionFactory connectionFactory;
    private volatile boolean schemaEnsured = false;

    public SqliteDiaryQueueRepository(SqliteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS diary_queue (
                    id TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    guild_id INTEGER NOT NULL,
                    channel_id INTEGER NOT NULL,
                    mode TEXT NOT NULL,
                    wake_up_time TEXT,
                    timeline_json TEXT NOT NULL,
                    achievements TEXT,
                    bad_points TEXT,
                    tomorrow_challenge TEXT,
                    status TEXT NOT NULL,
                    enqueued_at TEXT NOT NULL,
                    started_at TEXT,
                    completed_at TEXT,
                    error_message TEXT
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_diary_queue_status_enqueued
                ON diary_queue(status, enqueued_at);
            """);
        }
        schemaEnsured = true;
    }

    @Override
    public void enqueue(DiaryQueueEntry entry) {
        String sql = """
            INSERT INTO diary_queue
                (id, user_id, guild_id, channel_id, mode, wake_up_time, timeline_json,
                 achievements, bad_points, tomorrow_challenge, status, enqueued_at,
                 started_at, completed_at, error_message)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, entry.id);
                ps.setLong(2, entry.userId);
                ps.setLong(3, entry.guildId);
                ps.setLong(4, entry.channelId);
                ps.setString(5, entry.mode.name());
                ps.setString(6, entry.wakeUpTime);
                ps.setString(7, GSON.toJson(entry.timeline));
                ps.setString(8, entry.achievements);
                ps.setString(9, entry.badPoints);
                ps.setString(10, entry.tomorrowChallenge);
                ps.setString(11, entry.status != null ? entry.status.name() : DiaryQueueStatus.PENDING.name());
                ps.setString(12, entry.enqueuedAt != null ? entry.enqueuedAt.toString() : Instant.now().toString());
                ps.setString(13, entry.startedAt != null ? entry.startedAt.toString() : null);
                ps.setString(14, entry.completedAt != null ? entry.completedAt.toString() : null);
                ps.setString(15, entry.errorMessage);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("DiaryQueueEntryの追加に失敗しました (userId=" + entry.userId + "): " + e.getMessage());
        }
    }

    @Override
    public Optional<DiaryQueueEntry> loadNextPending() {
        String sql = """
            SELECT * FROM diary_queue
            WHERE status = ?
            ORDER BY enqueued_at ASC
            LIMIT 1
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, DiaryQueueStatus.PENDING.name());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(map(rs));
                    }
                    return Optional.empty();
                }
            }
        } catch (SQLException e) {
            logger.warning("DiaryQueueEntryの取得に失敗しました: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void markProcessing(String id) {
        String sql = """
            UPDATE diary_queue
            SET status = ?, started_at = ?
            WHERE id = ?
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, DiaryQueueStatus.PROCESSING.name());
                ps.setString(2, Instant.now().toString());
                ps.setString(3, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("DiaryQueueEntryの処理中マークに失敗しました (id=" + id + "): " + e.getMessage());
        }
    }

    @Override
    public void markDone(String id) {
        String sql = """
            UPDATE diary_queue
            SET status = ?, completed_at = ?
            WHERE id = ?
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, DiaryQueueStatus.DONE.name());
                ps.setString(2, Instant.now().toString());
                ps.setString(3, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("DiaryQueueEntryの完了マークに失敗しました (id=" + id + "): " + e.getMessage());
        }
    }

    @Override
    public void markFailed(String id, String errorMessage) {
        String sql = """
            UPDATE diary_queue
            SET status = ?, completed_at = ?, error_message = ?
            WHERE id = ?
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, DiaryQueueStatus.FAILED.name());
                ps.setString(2, Instant.now().toString());
                ps.setString(3, errorMessage);
                ps.setString(4, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("DiaryQueueEntryの失敗マークに失敗しました (id=" + id + "): " + e.getMessage());
        }
    }

    @Override
    public void resetOrphanedProcessing() {
        String sql = """
            UPDATE diary_queue
            SET status = ?, started_at = NULL
            WHERE status = ?
            """;
        try (Connection conn = connectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, DiaryQueueStatus.PENDING.name());
                ps.setString(2, DiaryQueueStatus.PROCESSING.name());
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    logger.info("再起動をまたいで処理中のままだったdiary_queueを" + updated + "件PENDINGに戻しました。");
                }
            }
        } catch (SQLException e) {
            logger.warning("diary_queueの処理中フラグのリセットに失敗しました: " + e.getMessage());
        }
    }

    private DiaryQueueEntry map(ResultSet rs) throws SQLException {
        DiaryQueueEntry e = new DiaryQueueEntry();
        e.id = rs.getString("id");
        e.userId = rs.getLong("user_id");
        e.guildId = rs.getLong("guild_id");
        e.channelId = rs.getLong("channel_id");
        e.mode = DiaryMode.valueOf(rs.getString("mode"));
        e.wakeUpTime = rs.getString("wake_up_time");

        Map<String, String> timeline = GSON.fromJson(rs.getString("timeline_json"),
                new TypeToken<LinkedHashMap<String, String>>() {}.getType());
        e.timeline = timeline != null ? timeline : new LinkedHashMap<>();

        e.achievements = rs.getString("achievements");
        e.badPoints = rs.getString("bad_points");
        e.tomorrowChallenge = rs.getString("tomorrow_challenge");
        e.status = DiaryQueueStatus.valueOf(rs.getString("status"));

        String enqueuedAt = rs.getString("enqueued_at");
        e.enqueuedAt = enqueuedAt != null ? Instant.parse(enqueuedAt) : null;
        String startedAt = rs.getString("started_at");
        e.startedAt = startedAt != null ? Instant.parse(startedAt) : null;
        String completedAt = rs.getString("completed_at");
        e.completedAt = completedAt != null ? Instant.parse(completedAt) : null;
        e.errorMessage = rs.getString("error_message");

        return e;
    }
}