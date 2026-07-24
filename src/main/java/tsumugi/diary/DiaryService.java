package tsumugi.diary;

import tsumugi.diary.model.DiaryQueueEntry;
import tsumugi.diary.model.DiaryRecord;
import tsumugi.diary.store.DiaryRepository;

import java.time.LocalDate;
import java.util.logging.Logger;

/**
 * diary_queueから取り出した1件の要約生成・DB保存を担う。
 * MemoryConsolidatorと同様、書き込みはここに一本化する。
 *
 * 変更点: 以前はDiarySession（インメモリ）から直接完了処理をしていたが、
 * 日記部屋へのアクセス集中に備えてSQL永続キュー経由に変更したため、
 * 入力はDiaryQueueEntry（DiaryQueueWorkerがSQLから読み出した1行）になった。
 */
public final class DiaryService {

    private static final Logger logger = Logger.getLogger(DiaryService.class.getName());

    private final DiaryRepository repository;
    private final DiarySummaryGenerator summaryGenerator;

    public DiaryService(DiaryRepository repository, DiarySummaryGenerator summaryGenerator) {
        this.repository = repository;
        this.summaryGenerator = summaryGenerator;
    }

    /**
     * diary_queueの1件を完了させる。総評生成（LLM呼び出し）はDiarySummaryGeneratorが
     * 持つLlmClient（呼び出し元でLlmLane.DIARYに紐づけたもの）経由で行われ、
     * このメソッドを呼んだスレッド（DiaryQueueWorker）上でブロッキング実行される。
     */
    public DiaryRecord completeQueueEntry(DiaryQueueEntry entry) {
        DiaryRecord record = new DiaryRecord(entry.userId, LocalDate.now(), entry.mode);
        record.wakeUpTime = entry.wakeUpTime;
        record.timeline.putAll(entry.timeline);
        record.achievements = entry.achievements;
        record.badPoints = entry.badPoints;
        record.tomorrowChallenge = entry.tomorrowChallenge;

        record.dailySummary = summaryGenerator.generate(record);
        repository.save(record);

        logger.info("日記セッションを完了・保存しました: userId=" + entry.userId);
        return record;
    }
}