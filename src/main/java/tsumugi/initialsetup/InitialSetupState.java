package tsumugi.initialsetup;

/**
 * 初期設定フローの状態。
 *
 * 想定される正常系の遷移:
 * NOT_STARTED → WAITING_CONSENT → WAITING_PRIVACY → WAITING_DATA_POLICY
 *   → WAITING_PROFILE → COMPLETED
 *
 * 不同意系の遷移:
 * WAITING_CONSENT → CONSENT_DECLINED → WAITING_KICK_GRACE → KICKED
 * （猶予期間中に同意すれば WAITING_CONSENT 等へ復帰させてよい）
 *
 * 退会（記名保持）→再入室系の遷移:
 * NOT_STARTED → (退会時に記名保持を選んでいたユーザーの再入室を検知)
 *   → WAITING_REJOIN_CONFIRM → (「はい」) → COMPLETED
 *   → WAITING_REJOIN_CONFIRM → (「いいえ」) → WAITING_NAME（通常の新規フローへ）
 *
 * 実際の遷移ルール自体は InitialSetupService 側が持ち、
 * この enum は「今どの状態か」を表すだけの値オブジェクトとする。
 */
public enum InitialSetupState {
    /** レコード未作成、あるいは参加直後でまだ何も始まっていない状態 */
    NOT_STARTED,

    /** 入室チャンネルでの名前入力待ち */
    WAITING_NAME,

    /** 利用規約への同意待ち */
    WAITING_CONSENT,

    /** プライバシーポリシーへの同意待ち */
    WAITING_PRIVACY,

    /** データ利用同意待ち */
    WAITING_DATA_POLICY,

    /** プロフィール等の初期設定入力待ち */
    WAITING_PROFILE,

    /** 初期設定完了、通常利用可能 */
    COMPLETED,

    /** 利用規約等に不同意を選んだ状態 */
    CONSENT_DECLINED,

    /** 不同意後の猶予期間（再同意を待っている） */
    WAITING_KICK_GRACE,

    /** Kick済み（記録として残す。DB上はここで終端） */
    KICKED,

    /** 退会時に記名保持を選んだユーザーが再入室し、記憶引継ぎの確認回答待ちの状態 */
    WAITING_REJOIN_CONFIRM
}
