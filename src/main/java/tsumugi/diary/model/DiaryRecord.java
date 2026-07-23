package tsumugi.diary.model;

import tsumugi.diary.DiaryMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 1回分の日記セッションの確定記録。
 * DiaryQueueEntry（diary_queueの1行）がDiaryQueueWorkerによって処理・完了した際に、
 * この形でdiary_recordsテーブルへ保存される。
 */
public final class DiaryRecord {

    public String id;
    public long userId;
    public LocalDate date;
    public DiaryMode mode;

    public String wakeUpTime;
    public Map<String, String> timeline = new LinkedHashMap<>();
    public String achievements;
    public String badPoints;
    public String tomorrowChallenge;

    /** DiarySummaryGeneratorが生成した総評文。 */
    public String dailySummary;

    public Instant createdAt;

    public DiaryRecord() {}

    public DiaryRecord(long userId, LocalDate date, DiaryMode mode) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.date = date;
        this.mode = mode;
        this.createdAt = Instant.now();
    }
}
