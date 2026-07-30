package tsumugi.broadcast;

/**
 * お知らせ配信フローの状態。
 *
 * NOT_IN_SESSION → (原文投稿) → WAITING_REVIEW（LLM校正中）
 *   → WAITING_CONFIRM（校正結果を提示し、はい/いいえ待ち）
 *       ├ 「はい」 → 配信実行 → NOT_IN_SESSION（セッション終了）
 *       ├ 「いいえ」 → WAITING_REVISION（修正文の投稿待ち）
 *       │     → (修正文投稿) → WAITING_REVIEW に戻る（再校正）
 *       └ それ以外の入力 → WAITING_CONFIRMのまま、再度はい/いいえを促す
 *
 * 「いいえ」を選ぶたびに何度でも往復修正できる。
 * インメモリ管理のみ（DiarySessionと同じ方針）。Bot再起動をまたぐとセッションは失われる。
 */
public enum BroadcastState {
    /** セッションなし（通常状態） */
    NOT_IN_SESSION,

    /** LLMによる文章チェック中 */
    WAITING_REVIEW,

    /** 校正結果を提示済み、配信可否（はい/いいえ）の回答待ち */
    WAITING_CONFIRM,

    /** 「いいえ」を受けて、修正後の文面の投稿待ち */
    WAITING_REVISION
}
