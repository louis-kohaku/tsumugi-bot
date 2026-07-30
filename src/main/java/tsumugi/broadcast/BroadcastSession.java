package tsumugi.broadcast;

/**
 * 1回分の配信手続きの進行データ。DBには保存せず、
 * BroadcastServiceがguildIdをキーにインメモリで保持する（DiarySessionと同じ方針）。
 * 配信完了・破棄で消える。
 */
public final class BroadcastSession {

    public final long guildId;
    public BroadcastState state = BroadcastState.NOT_IN_SESSION;

    /** 最初にKohakuが送った原文（履歴保存用に保持しておく） */
    public String originalContent;

    /** 直近でLLMがチェックした結果、配信案として提示している文面 */
    public String currentDraft;

    public BroadcastSession(long guildId) {
        this.guildId = guildId;
    }
}
