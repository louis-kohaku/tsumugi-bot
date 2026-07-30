package tsumugi.llm;

/**
 * LaneLlmDispatcherにおけるタスクの種別（レーン）。
 *
 * rank()の値が小さいほど「空いた時に優先して選ばれる」。
 * 注意: これは優先度であって割り込みではない。既に実行中のタスクを
 * 中断してレーンの高いものへ差し替えることは一切しない
 * （LaneLlmDispatcherのクラスコメント参照）。
 *
 *  - CHAT     : 通常会話の応答生成・記憶検索。最優先。
 *  - BROADCAST: お知らせ文のLLM校正チェック。CHATと同格の即時レーン。
 *               Kohaku（管理者）操作のためCHATと衝突する頻度は低いが、
 *               優先度はCHATと同格とし、待たせすぎないようにする。
 *               中断は不要（一度始まった校正は最後まで完了させる）。
 *  - DIARY    : 日記の総評生成。CHAT/BROADCASTと同格の即時レーンだが、重量モデルを使う想定。
 *               一度実行が始まったら、他のCHATが来ても最後まで完了させる。
 *  - HEAVY    : Evidence抽出（性格・感情等の分析）等、緊急性の低い重い処理。
 *               CHAT/BROADCAST/DIARYが一切無く、かつ一定時間アイドルが続いた場合にのみ着手する
 *               （モデルの無駄な入れ替えを避けるため）。
 */
public enum LlmLane {
    CHAT(0),
    BROADCAST(0),
    DIARY(1),
    HEAVY(2);

    private final int rank;

    LlmLane(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
