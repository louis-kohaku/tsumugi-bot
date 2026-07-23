package tsumugi.llm;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * CHAT/DIARY/HEAVY各レーンのLLM呼び出しを、単一のワーカースレッドに集約して直列実行する。
 *
 * 優先度ルール:
 *  - CHATとDIARYは同格の「即時キュー」に積まれ、FIFOで処理される。
 *  - HEAVYは即時キューが空のときのみ処理される（＝会話・日記が無い間の隙間実行）。
 *  - 「実行中のタスクは中断しない」という要件は、ワーカーが単一スレッドであることで
 *    自然に満たされる（新しいタスクが来ても、実行中のCallableの終了を待ってから次を取る）。
 *
 * 呼び出し元スレッド（DiaryQueueWorker/EvidenceExtractor/ConversationEngine等）から見ると
 * call()/embed()は同期呼び出しであり、内部でタスクをキューに積んで完了を待つ。
 */
public final class LaneLlmDispatcher {

    private static final Logger logger = Logger.getLogger(LaneLlmDispatcher.class.getName());
    private static final long IDLE_POLL_MILLIS = 200;

    private final Map<LlmLane, LlmClient> llmClients;
    private final Map<LlmLane, EmbeddingClient> embeddingClients;

    private final Object lock = new Object();
    private final Queue<Task> immediateQueue = new LinkedList<>(); // CHAT / DIARY
    private final Queue<Task> heavyQueue = new LinkedList<>();     // HEAVY

    private volatile boolean running = true;
    private final Thread workerThread;

    public LaneLlmDispatcher(Map<LlmLane, LlmClient> llmClients, Map<LlmLane, EmbeddingClient> embeddingClients) {
        this.llmClients = llmClients;
        this.embeddingClients = embeddingClients;
        this.workerThread = new Thread(this::loop, "llm-lane-dispatcher");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    public String call(LlmLane lane, String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        Object result = submit(lane, () -> llmClients.get(lane).call(systemPrompt, userPrompt, maxTokens, temperature));
        return (String) result;
    }

    public float[] embed(LlmLane lane, String text) {
        Object result = submit(lane, () -> embeddingClients.get(lane).embed(text));
        return (float[]) result;
    }

    private Object submit(LlmLane lane, Callable<Object> action) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        Task task = new Task(lane, future, action);
        synchronized (lock) {
            if (lane == LlmLane.HEAVY) {
                heavyQueue.add(task);
            } else {
                immediateQueue.add(task);
            }
            lock.notifyAll();
        }
        try {
            return future.join();
        } catch (RuntimeException e) {
            logger.warning("LLM呼び出しの待機中にエラーが発生しました (lane=" + lane + "): " + e.getMessage());
            return null;
        }
    }

    private void loop() {
        while (running) {
            Task task = pollNext();
            if (task == null) {
                sleepQuietly();
                continue;
            }
            runTask(task);
        }
    }

    private Task pollNext() {
        synchronized (lock) {
            if (!immediateQueue.isEmpty()) return immediateQueue.poll();
            if (!heavyQueue.isEmpty()) return heavyQueue.poll();
            return null;
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(IDLE_POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runTask(Task task) {
        try {
            Object result = task.action.call();
            task.future.complete(result);
        } catch (Exception e) {
            logger.warning("LLMタスクの実行に失敗しました (lane=" + task.lane + "): " + e.getMessage());
            task.future.complete(null);
        }
    }

    public void shutdown() {
        running = false;
        workerThread.interrupt();
    }

    private static final class Task {
        final LlmLane lane;
        final CompletableFuture<Object> future;
        final Callable<Object> action;

        Task(LlmLane lane, CompletableFuture<Object> future, Callable<Object> action) {
            this.lane = lane;
            this.future = future;
            this.action = action;
        }
    }
}
