package tsumugi.diary.model;

import tsumugi.diary.DiaryMode;
import tsumugi.diary.DiaryQueueStatus;
import tsumugi.diary.DiarySession;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * diary_queue（SQL永続キュー）1行分のデータ。
 *
 * 日記セッションが完了した時点でこのオブジェクトを組み立ててSQLへ保存し、
 * インメモリのDiarySessionは即座に破棄する（アクセス集中時にメモリを圧迫しないため）。
 * 実際の要約生成（LLM呼び出し）はDiaryQueueWorkerが後からこの行を読み出して行う。
 */
public final class DiaryQueueEntry {

    public String id;
    public long userId;
    public long guildId;
    /** 結果を投稿するプライベート日記部屋のチャンネルID */
    public long channelId;
    public DiaryMode mode;

    public String wakeUpTime;
    public Map<String, String> timeline = new LinkedHashMap<>();
    public String achievements;
    public String badPoints;
    public String tomorrowChallenge;

    public DiaryQueueStatus status;
    public Instant enqueuedAt;
    public Instant startedAt;
    public Instant completedAt;
    public String errorMessage;

    public DiaryQueueEntry() {}

    /** セッション完了時にDiaryManagerから呼ぶファクトリ。 */
    public static DiaryQueueEntry fromSession(DiarySession session, long guildId) {
        DiaryQueueEntry e = new DiaryQueueEntry();
        e.id = UUID.randomUUID().toString();
        e.userId = session.userId;
        e.guildId = guildId;
        e.channelId = session.channelId;
        e.mode = session.mode;
        e.wakeUpTime = session.wakeUpTime != null ? session.wakeUpTime.toString() : null;
        e.timeline.putAll(session.timeline);
        e.achievements = session.achievements;
        e.badPoints = session.badPoints;
        e.tomorrowChallenge = session.tomorrowChallenge;
        e.status = DiaryQueueStatus.PENDING;
        e.enqueuedAt = Instant.now();
        return e;
    }
}
