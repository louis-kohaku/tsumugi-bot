package tsumugi.diary.store;

import tsumugi.diary.model.DiaryQueueEntry;

import java.util.Optional;

/**
 * diary_queue（SQL永続キュー）の永続化インタフェース。
 * 日記部屋へのアクセスが集中しても、待機中のセッションデータをJVMメモリではなく
 * ここに保持することで、Bot再起動をまたいでもキューが失われないようにする。
 */
public interface DiaryQueueRepository {

    void enqueue(DiaryQueueEntry entry);

    /** enqueuedAtが最も古いPENDING行を1件返す。無ければ空。 */
    Optional<DiaryQueueEntry> loadNextPending();

    void markProcessing(String id);

    void markDone(String id);

    void markFailed(String id, String errorMessage);

    /**
     * Bot起動直後に1度だけ呼ぶ。前回の異常終了等でPROCESSINGのまま止まっていた行を
     * PENDINGに戻し、再度処理対象にする。
     */
    void resetOrphanedProcessing();
}
