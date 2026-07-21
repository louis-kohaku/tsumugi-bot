package tsumugi.withdrawal.store;

import tsumugi.withdrawal.model.WithdrawalRecord;

import java.util.List;

/**
 * WithdrawalRecordの永続化インタフェース。
 * initialsetup.store.InitialSetupRepositoryと同様、上位層はこの抽象にのみ依存する。
 */
public interface WithdrawalRepository {

    /** 存在しなければ新規（NOT_REQUESTED）のレコードを生成して返す。 */
    WithdrawalRecord load(long userId, long guildId);

    void save(WithdrawalRecord record);

    void delete(long userId, long guildId);

    /**
     * 「削除」を選んだがまだ実際には削除されていない（dataDeletedAtがnull）レコード一覧。
     * Bot再起動時に3日後自動削除のスケジュールを再構築するために使う想定。
     */
    List<WithdrawalRecord> loadPendingDeletions();
}
