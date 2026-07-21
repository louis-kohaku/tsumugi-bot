package tsumugi.initialsetup;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 利用規約・プライバシーポリシー・データ利用同意の管理を担うクラス（骨組み）。
 *
 * TODO: 実際の同意記録はDB（例えば initial_setup とは別テーブル consent_log）に
 *       永続化し、いつ・どのバージョンの規約に同意したかを保持できるようにする。
 * TODO: Google Formsやアプリ版UIからの同意入力を受け付ける口を追加する。
 *
 * 現時点ではInitialSetupServiceから呼ばれる差し替え可能な口だけを用意し、
 * 常に「同意なし」として扱うことでフローの骨組みを壊さないようにする。
 */
public final class ConsentManager {

    private static final Logger logger = Logger.getLogger(ConsentManager.class.getName());

    public enum ConsentType {
        TERMS_OF_SERVICE,
        PRIVACY_POLICY,
        DATA_USAGE
    }

    // TODO: 仮のインメモリ実装。再起動で消えるためDB実装に置き換えること。
    private final Map<Long, Map<ConsentType, Boolean>> consentByUser = new ConcurrentHashMap<>();

    /**
     * 同意結果を記録する。
     * TODO: 実際の同意画面・ボタン操作と接続する。
     */
    public void recordConsent(long userId, ConsentType type, boolean agreed) {
        consentByUser
                .computeIfAbsent(userId, k -> new EnumMap<>(ConsentType.class))
                .put(type, agreed);
        logger.info("[TODO実装] 同意記録: userId=" + userId + " type=" + type + " agreed=" + agreed);
    }

    public boolean hasConsented(long userId, ConsentType type) {
        Map<ConsentType, Boolean> record = consentByUser.get(userId);
        return record != null && Boolean.TRUE.equals(record.get(type));
    }

    /** 必須の同意（利用規約・プライバシーポリシー・データ利用）が全て揃っているか。 */
    public boolean hasAllRequiredConsents(long userId) {
        for (ConsentType type : ConsentType.values()) {
            if (!hasConsented(userId, type)) return false;
        }
        return true;
    }

    public void clear(long userId) {
        consentByUser.remove(userId);
    }
}
