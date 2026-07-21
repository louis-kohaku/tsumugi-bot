package tsumugi.withdrawal;

/**
 * 退会時に選べるデータの扱い3択。
 */
public enum WithdrawalDataChoice {
    /** 匿名化して保持（本人と紐付かない研究データとして残す） */
    ANONYMIZE,

    /** 記名で保持（次回参加時に記憶を引き継げるようにする） */
    KEEP_NAMED,

    /** 削除（本人に関する全データを削除する） */
    DELETE;

    /** ユーザーが入力する「1」「2」「3」からのパース。該当なしはnull。 */
    public static WithdrawalDataChoice fromInput(String rawInput) {
        if (rawInput == null) return null;
        String trimmed = rawInput.strip();
        return switch (trimmed) {
            case "1" -> ANONYMIZE;
            case "2" -> KEEP_NAMED;
            case "3" -> DELETE;
            default -> null;
        };
    }
}
