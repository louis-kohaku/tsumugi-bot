package tsumugi.withdrawal;

/**
 * 退会フローの状態。
 *
 * 正常系:
 * NOT_STARTED → WAITING_CHOICE →
 *   ├ COMPLETED_ANONYMIZED（匿名で保存を選択・即時処理）
 *   ├ PENDING_ADMIN_REVIEW（監査の上で削除を選択）→ COMPLETED_DELETED（管理者承認 or 3日経過）
 *   └ COMPLETED_RETAINED（次の利用のため保存を選択）
 *
 * WAITING_CHOICE / PENDING_ADMIN_REVIEW のまま3日経過した場合は、
 * どちらも「通常削除」（COMPLETED_DELETED）として自動処理する。
 */
public enum WithdrawalState {
    /** 退会フロー未開始 */
    NOT_STARTED,

    /** 退会専用チャンネルでの選択待ち */
    WAITING_CHOICE,

    /** 「管理者による監査の上で削除」を選択し、管理者の対応待ち */
    PENDING_ADMIN_REVIEW,

    /** 匿名化して研究用データのみ残し、個人特定データは削除済み */
    COMPLETED_ANONYMIZED,

    /** 通常削除（監査待ちのまま期限切れ、または管理者承認）済み */
    COMPLETED_DELETED,

    /** 次回利用のためデータを保持することを選択済み */
    COMPLETED_RETAINED
}
