package tsumugi.diary;

/** diary_queue（SQL永続キュー）1行の処理状態。 */
public enum DiaryQueueStatus {
    /** 順番待ち */
    PENDING,
    /** 処理中（DiaryQueueWorkerが1件だけ同時にこの状態を持つ） */
    PROCESSING,
    /** 完了・結果投稿済み */
    DONE,
    /** 失敗（謝罪メッセージ投稿済み） */
    FAILED
}
