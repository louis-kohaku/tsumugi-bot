package tsumugi.broadcast.store;

import tsumugi.broadcast.model.BroadcastHistoryEntry;

/**
 * 配信履歴（BroadcastHistoryEntry）の永続化インタフェース。
 * ログとして残すことのみが目的のため、保存のみを提供する（一覧取得等のAPIは持たない）。
 */
public interface BroadcastRepository {

    void save(BroadcastHistoryEntry entry);
}
