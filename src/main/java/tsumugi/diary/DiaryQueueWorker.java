package tsumugi.diary;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.diary.model.DiaryQueueEntry;
import tsumugi.diary.model.DiaryRecord;
import tsumugi.diary.store.DiaryQueueRepository;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * diary_queue（SQL永続キュー）を1件ずつ取り出し、順番に処理する専用ワーカースレッド。
 *
 * 実際の要約生成（LLM呼び出し）はDiaryService.completeQueueEntry()に委譲する。
 * DiaryServiceが内部で使うLlmClientはLlmLane.DIARYに紐づいたものである前提で、
 * 「一度実行を始めたタスクは最後まで中断しない」という保証はLaneLlmDispatcher側が担う。
 *
 * このワーカー自身は「SQLキューから1件ずつ順番に取り出す」ことだけを担当し、
 * 実行順の優先度（会話 > 日記 > 感情分析等）の制御には一切関与しない
 * （そちらはLaneLlmDispatcherの責務）。
 */
public final class DiaryQueueWorker {

    private static final Logger logger = Logger.getLogger(DiaryQueueWorker.class.getName());
    private static final long EMPTY_POLL_INTERVAL_MILLIS = 2000;

    private final DiaryQueueRepository queueRepository;
    private final DiaryService diaryService;
    private final DiaryChannelService channelService;

    private volatile JDA jda;
    private volatile boolean running = false;
    private Thread workerThread;

    public DiaryQueueWorker(DiaryQueueRepository queueRepository,
                             DiaryService diaryService,
                             DiaryChannelService channelService) {
        this.queueRepository = queueRepository;
        this.diaryService = diaryService;
        this.channelService = channelService;
    }

    public void setJda(JDA jda) {
        this.jda = jda;
    }

    /**
     * Bot起動完了直後に呼ぶ。再起動をまたいでPROCESSINGのまま止まっていた行をPENDINGへ戻してから
     * ワーカースレッドを起動する。
     */
    public void start() {
        queueRepository.resetOrphanedProcessing();
        running = true;
        workerThread = new Thread(this::loop, "diary-queue-worker");
        workerThread.setDaemon(true);
        workerThread.start();
        logger.info("日記キューワーカーを起動しました。");
    }

    private void loop() {
        while (running) {
            Optional<DiaryQueueEntry> next;
            try {
                next = queueRepository.loadNextPending();
            } catch (RuntimeException e) {
                logger.warning("日記キューの読み込みに失敗しました: " + e.getMessage());
                sleepQuietly(EMPTY_POLL_INTERVAL_MILLIS);
                continue;
            }

            if (next.isEmpty()) {
                sleepQuietly(EMPTY_POLL_INTERVAL_MILLIS);
                continue;
            }

            processOne(next.get());
        }
    }

    private void processOne(DiaryQueueEntry entry) {
        queueRepository.markProcessing(entry.id);
        logger.info("日記の処理を開始します: userId=" + entry.userId + " entryId=" + entry.id);
        try {
            // ここがブロッキング呼び出し。LlmLane.DIARYで実行され、
            // 一度始まったら完了するまで（会話が来ても）中断されない。
            DiaryRecord record = diaryService.completeQueueEntry(entry);
            queueRepository.markDone(entry.id);
            postResult(entry, record);
            logger.info("日記の処理が完了しました: userId=" + entry.userId + " entryId=" + entry.id);
        } catch (RuntimeException e) {
            logger.warning("日記の処理に失敗しました (entryId=" + entry.id + "): " + e.getMessage());
            queueRepository.markFailed(entry.id, e.getMessage());
            postFailure(entry);
        }
    }

    private void postResult(DiaryQueueEntry entry, DiaryRecord record) {
        TextChannel channel = resolveChannel(entry);
        if (channel == null) return;
        channelService.postMessage(channel, record.dailySummary);
        channelService.postMessage(channel, "この日記部屋は1分後に削除されます😊");
        channelService.scheduleDiaryRoomDelete(channel);
    }

    private void postFailure(DiaryQueueEntry entry) {
        TextChannel channel = resolveChannel(entry);
        if (channel == null) return;
        channelService.postMessage(channel,
                "ごめんなさい、総評の作成中にエラーが発生してしまいました…。もう一度 /日記 からやり直していただけますか？");
    }

    private TextChannel resolveChannel(DiaryQueueEntry entry) {
        if (jda == null) return null;
        TextChannel channel = jda.getTextChannelById(entry.channelId);
        if (channel == null) {
            logger.warning("日記部屋が見つからないため結果の投稿をスキップしました: channelId=" + entry.channelId);
        }
        return channel;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}
