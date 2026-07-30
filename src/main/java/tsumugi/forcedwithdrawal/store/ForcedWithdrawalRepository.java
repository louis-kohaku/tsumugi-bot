package tsumugi.forcedwithdrawal.store;

import tsumugi.forcedwithdrawal.ForcedWithdrawalState;
import tsumugi.forcedwithdrawal.model.ForcedWithdrawalRecord;

import java.util.List;

/**
 * ForcedWithdrawalRecordの永続化インタフェース。
 * 上位層（ForcedWithdrawalService）はこの抽象にのみ依存する。
 */
public interface ForcedWithdrawalRepository {

    void save(ForcedWithdrawalRecord record);

    ForcedWithdrawalRecord loadById(String id);

    /** 状態指定での一括取得（再起動時のスケジュール再構築用）。 */
    List<ForcedWithdrawalRecord> loadByState(ForcedWithdrawalState state);

    void delete(String id);
}
