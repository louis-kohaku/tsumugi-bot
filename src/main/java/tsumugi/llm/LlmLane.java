package tsumugi.llm;

/**
<<<<<<< HEAD
 * LLM呼び出しの優先度レーン。
 *
 *  - CHAT : 通常会話の応答生成・記憶検索。最優先の即時レーン。
 *  - DIARY: 日記の総評生成。CHATと同格の即時レーン。
 *  - HEAVY: Evidence抽出（性格・感情分析）等。CHAT/DIARYが無い時にのみ着手する。
 *
 * 実際の優先度制御はLaneLlmDispatcherが行う。
 */
public enum LlmLane {
    CHAT,
    DIARY,
    HEAVY
=======
 * LaneLlmDispatcherにおけるタスクの種別（レーン）。
 *
 * rank()の値が小さいほど「空いた時に優先して選ばれる」。
 * 注意: これは優先度であって割り込みではない。既に実行中のタスクを
 * 中断してレーンの高いものへ差し替えることは一切しない
 * （LaneLlmDispatcherのクラスコメント参照）。
 *
 *  - CHAT : 通常会話の応答生成・記憶検索。最優先。
 *  - DIARY: 日記の総評生成。CHATと同格の即時レーンだが、重量モデルを使う想定。
 *           一度実行が始まったら、他のCHATが来ても最後まで完了させる。
 *  - HEAVY: Evidence抽出（性格・感情等の分析）等、緊急性の低い重い処理。
 *           CHAT/DIARYが一切無く、かつ一定時間アイドルが続いた場合にのみ着手する
 *           （モデルの無駄な入れ替えを避けるため）。
 */
public enum LlmLane {
    CHAT(0),
    DIARY(1),
    HEAVY(2);

    private final int rank;

    LlmLane(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
>>>>>>> 845a43dc06155023d2c10e267d55ed61bb35cf5c
}
