package tsumugi.withdrawal.store;

import tsumugi.withdrawal.WithdrawalState;
import tsumugi.withdrawal.model.WithdrawalRecord;

import java.time.Instant;
import java.util.List;

/**
 * WithdrawalRecordの永続化インタフェース。
 * InitialSetupRepositoryと同様、上位層（WithdrawalService）はこの抽象にのみ依存する。
 */
public interface WithdrawalRepository {

    /** 存在しなければ新規（NOT_STARTED）のレコードを生成して返す。 */
    WithdrawalRecord load(long userId, long guildId);

    void save(WithdrawalRecord record);

    void delete(long userId, long guildId);

    /** 状態指定での一括取得（管理者一覧・再起動時のスケジュール再構築用）。 */
    List<WithdrawalRecord> loadByState(WithdrawalState state);

    /** 指定日時よりdeadlineAtが過去で、かつ未確定状態（WAITING_CHOICE/PENDING_ADMIN_REVIEW）のレコード一覧。 */
    List<WithdrawalRecord> loadExpired(Instant now);
}
