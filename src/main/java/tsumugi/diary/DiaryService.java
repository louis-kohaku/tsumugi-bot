package tsumugi.diary;

import tsumugi.diary.model.DiaryRecord;
import tsumugi.diary.store.DiaryRepository;

import java.time.LocalDate;
import java.util.logging.Logger;

/**
 * セッション完了時の総評生成・DB保存を担う。
 * MemoryConsolidatorと同様、書き込みはここに一本化する。
 */
public final class DiaryService {

    private static final Logger logger = Logger.getLogger(DiaryService.class.getName());

    private final DiaryRepository repository;
    private final DiarySummaryGenerator summaryGenerator;

    public DiaryService(DiaryRepository repository, DiarySummaryGenerator summaryGenerator) {
        this.repository = repository;
        this.summaryGenerator = summaryGenerator;
    }

    /** セッション完了時に呼ぶ。総評を生成し、DiaryRecordとして保存する。 */
    public DiaryRecord completeSession(DiarySession session) {
        DiaryRecord record = new DiaryRecord(session.userId, LocalDate.now(), session.mode);
        record.wakeUpTime = session.wakeUpTime != null ? session.wakeUpTime.toString() : null;
        record.timeline.putAll(session.timeline);
        record.achievements = session.achievements;
        record.badPoints = session.badPoints;
        record.tomorrowChallenge = session.tomorrowChallenge;

        record.dailySummary = summaryGenerator.generate(record);
        repository.save(record);

        logger.info("日記セッションを完了・保存しました: userId=" + session.userId);
        return record;
    }
}
