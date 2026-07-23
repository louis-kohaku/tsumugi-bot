package tsumugi.diary.model;

import tsumugi.diary.DiaryMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 1回分の日記セッションの完了結果を表す永続化用レコード。
 * セッション進行中の一時データはDiarySession（インメモリ）が持ち、
 * 完了時にこのRecordへ変換してDB保存する。
 */
public final class DiaryRecord {

    public String id;
    public long userId;
    public LocalDate date;
    public DiaryMode mode;

    public String wakeUpTime;

    /** キー: "07:00-09:00" のような時間帯ラベル、値: ユーザーの自由入力 */
    public Map<String, String> timeline = new LinkedHashMap<>();

    public String achievements;
    public String badPoints;
    public String tomorrowChallenge;
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
