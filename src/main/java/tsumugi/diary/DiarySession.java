package tsumugi.diary;

import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1ユーザー分の日記セッション進行データ。DBには保存せず、
 * DiarySessionManagerがユーザーIDをキーにインメモリで保持する。
 * セッションが完了（または中断）したら破棄される。
 */
public final class DiarySession {

    public final long userId;
    public final DiaryMode mode;
    public long channelId;
    public DiaryState state = DiaryState.WAITING_WAKE_TIME;

    public LocalTime wakeUpTime;
    public final Map<String, String> timeline = new LinkedHashMap<>();
    public String achievements;
    public String badPoints;
    public String tomorrowChallenge;

    /** Step2で使う、まだ聞いていない2時間スロットのキュー（"07:00-09:00"形式） */
    public final Deque<String> pendingSlots = new ArrayDeque<>();

    public DiarySession(long userId, DiaryMode mode) {
        this.userId = userId;
        this.mode = mode;
    }
}
