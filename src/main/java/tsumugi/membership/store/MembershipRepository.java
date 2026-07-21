package tsumugi.membership.store;

import tsumugi.membership.MembershipEvent;

import java.util.List;

/**
 * MembershipEvent（入退室ログ）の永続化インタフェース。
 * memory.store.* / initialsetup.store.* と同様、上位層はこの抽象にのみ依存する。
 */
public interface MembershipRepository {

    void save(MembershipEvent event);

    /** 指定ユーザーの最新イベント（occurredAt最大）を1件返す。記録が無ければnull。 */
    MembershipEvent loadLatest(long userId);

    /** 指定ユーザーの全イベントを時系列昇順で返す（監査・確認用）。 */
    List<MembershipEvent> loadHistory(long userId);
}
