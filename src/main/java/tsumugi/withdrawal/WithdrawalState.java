package tsumugi.withdrawal;

/**
 * 退会フローの状態。
 *
 * NOT_REQUESTED → (「退会」発言) → WAITING_CHOICE → (3択回答) → CONFIRMED → (1分後) → KICKED
 */
public enum WithdrawalState {
    /** 退会申請なし（通常状態） */
    NOT_REQUESTED,

    /** 専用チャンネル作成済み、データの扱い（3択）の回答待ち */
    WAITING_CHOICE,

    /** 回答済み・退会確定。キック待ちの状態 */
    CONFIRMED,

    /** キック実行済み（記録として残す） */
    KICKED
}
