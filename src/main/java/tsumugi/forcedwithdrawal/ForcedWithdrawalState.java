package tsumugi.forcedwithdrawal;

/**
 * 管理者発の強制退会フローの状態。
 * 本人発のtsumugi.withdrawal.WithdrawalStateとは独立したドメイン。
 *
 * NOT_STARTED → WAITING_REASON → WAITING_CONFIRM →
 *   ├ CONFIRMED（24時間後キック予約済み）→ EXECUTED（匿名化・削除・キック完了）
 *   └ CANCELLED（管理者が「いいえ」を選択）
 */
public enum ForcedWithdrawalState {
    NOT_STARTED,
    WAITING_REASON,
    WAITING_CONFIRM,
    CONFIRMED,
    EXECUTED,
    CANCELLED
}
