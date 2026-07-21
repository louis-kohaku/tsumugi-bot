package tsumugi.initialsetup.store;

import tsumugi.initialsetup.InitialSetupState;
import tsumugi.initialsetup.model.InitialSetupRecord;

import java.util.List;

/**
 * InitialSetupRecordの永続化インタフェース。
 * memory.store.* と同様、上位層（InitialSetupService）はこの抽象にのみ依存する。
 * 将来Postgres等へ差し替える場合もこの境界を変えなくてよい。
 */
public interface InitialSetupRepository {

    /** 存在しなければ新規（NOT_STARTED）のレコードを生成して返す。 */
    InitialSetupRecord load(long userId, long guildId);

    void save(InitialSetupRecord record);

    void delete(long userId, long guildId);

    /** 猶予期間の一括処理（Kickバッチ等）のために、状態指定で複数件取得できるようにする。 */
    List<InitialSetupRecord> loadByState(InitialSetupState state);
}
