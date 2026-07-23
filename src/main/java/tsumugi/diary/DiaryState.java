package tsumugi.diary;

/** 日記セッションの進行状態。DBには永続化せず、DiarySessionManagerがインメモリで管理する。 */
public enum DiaryState {
    NOT_IN_SESSION,
    WAITING_WAKE_TIME,
    WAITING_TIMELINE,
    WAITING_ACHIEVEMENTS,
    WAITING_BAD_POINTS,
    WAITING_TOMORROW_CHALLENGE,
    GENERATING_SUMMARY,
    COMPLETED
}
