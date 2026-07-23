package tsumugi.diary.store;

import tsumugi.diary.model.DiaryRecord;

import java.util.List;

/**
 * DiaryRecordの永続化インタフェース。
 * WithdrawalRepository/InitialSetupRepositoryと同様、上位層（DiaryService）はこの抽象にのみ依存する。
 */
public interface DiaryRepository {

    void save(DiaryRecord record);

    /** 指定ユーザーの日記記録を日付昇順で取得する。 */
    List<DiaryRecord> loadByUser(long userId);
}
