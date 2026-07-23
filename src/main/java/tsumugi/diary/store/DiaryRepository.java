package tsumugi.diary.store;

import tsumugi.diary.model.DiaryRecord;

import java.util.List;

/** DiaryRecordの永続化インタフェース。上位層（DiaryService）はこの抽象にのみ依存する。 */
public interface DiaryRepository {

    void save(DiaryRecord record);

    List<DiaryRecord> loadByUser(long userId);
}
