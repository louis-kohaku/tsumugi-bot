package tsumugi.llm;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * LM Studioへのリクエストを「常に1本だけ・順番に」実行するためのディスパッチャ。
 *
 * ═══════════════════════════════════════
 *  守るべきルールはただ1つ：「実行中のタスクは何が来ても中断しない」
 * ═══════════════════════════════════════
 *
 * ワーカースレッドは1本のみ。LM Studioへの同時リクエスト数は常に1になる。
 * 「会話を優先する」というのは、あくまで
 *   ワーカーが空いた瞬間に「次に何を実行するか」を選ぶときの順序
 * の話であり、既に走り始めている処理（会話でも日記でも）を後から来た
 * 会話タスクのために中断する、ということは一切しない。
 *
 * レーン間の関係:
 *  - CHAT / DIARY は「同格の即時レーン」。ワーカーが空いていれば、
 *    待っている中で最もrankが小さいもの（＝CHATが先、次点でDIARY）から選ぶ。
 *    実行を始めたら最後まで完了させる（日記が動いていれば会話はその完了を待つ）。
 *  - HEAVY（Evidence抽出等）は、CHAT/DIARYが一切無い状態で、かつ
 *    「直近にCHAT/DIARYが完了してから一定時間（HEAVY_IDLE_THRESHOLD）が経過している」
 *    場合にのみ着手する。これはLM Studio側のモデル入れ替えコストを避けるための猶予で、
 *    会話がぽつぽつ続いている間はHEAVYには手を出さない、という挙動になる。
 */
public final class LaneLlmDispatcher {

    private static final Logger logger = Logger.getLogger(LaneLlmDispatcher.class.getName());

    /** CHAT/DIARYが完了してから、この時間アイドルが続かない限りHEAVYには着手しない。 */
    private static final Duration HEAVY_IDLE_THRESHOLD = Duration.ofSeconds(15);

    /** キューが空のときの最大待機（この間隔でCHAT/DIARYの到着やシャットダウンを確認する）。 */
    private static final long POLL_TIMEOUT_MILLIS = 300;

    /** HEAVYの手番だがまだアイドル猶予を満たしていない場合の再チェック間隔。 */
    private static final long HEAVY_RECHECK_INTERVAL_MILLIS = 500;

    private final Map<LlmLane, LlmClient> llmClients;
    private final Map<LlmLane, EmbeddingClient> embeddingClients;

    private final PriorityBlockingQueue<Task<?>> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();

    private volatile Instant lastNonHeavyFinishedAt = Instant.now();
    private volatile boolean running = true;
    private final Thread worker;

    public LaneLlmDispatcher(Map<LlmLane, LlmClient> llmClients, Map<LlmLane, EmbeddingClient> embeddingClients) {
        this.llmClients = new EnumMap<>(llmClients);
        this.embeddingClients = new EnumMap<>(embeddingClients);
        this.worker = new Thread(this::loop, "llm-dispatcher");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** 同期呼び出し。内部でキューに積み、ワーカースレッドでの実行完了を待つ。失敗時はnullを返す。 */
    public String call(LlmLane lane, String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        CallTask task = new CallTask(lane, sequence.incrementAndGet(), systemPrompt, userPrompt, maxTokens, temperature);
        enqueue(task);
        return task.future.join();
    }

    /** 同期呼び出し。内部でキューに積み、ワーカースレッドでの実行完了を待つ。失敗時はnullを返す。 */
    public float[] embed(LlmLane lane, String text) {
        EmbedTask task = new EmbedTask(lane, sequence.incrementAndGet(), text);
        enqueue(task);
        return task.future.join();
    }

    private void enqueue(Task<?> task) {
        queue.put(task); // PriorityBlockingQueueは無限容量なのでブロックしない
    }

    // ═══════════════════════════════════════
    //  ワーカーループ
    // ═══════════════════════════════════════

    private void loop() {
        while (running) {
            Task<?> head;
            try {
                head = queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue;
            }
            if (head == null) continue; // 何も無ければ次のポーリングへ

            if (head.lane == LlmLane.HEAVY && !heavyIdleThresholdSatisfied()) {
                // まだCHAT/DIARYの直後すぎる。HEAVYタスクをキューに戻して少し待つ。
                // この間にCHAT/DIARYが積まれれば、rankが小さいためそちらが先に選ばれる。
                queue.put(head);
                sleepQuietly(HEAVY_RECHECK_INTERVAL_MILLIS);
                continue;
            }

            execute(head);

            if (head.lane != LlmLane.HEAVY) {
                lastNonHeavyFinishedAt = Instant.now();
            }
        }
    }

    private boolean heavyIdleThresholdSatisfied() {
        return Duration.between(lastNonHeavyFinishedAt, Instant.now()).compareTo(HEAVY_IDLE_THRESHOLD) >= 0;
    }

    private void execute(Task<?> task) {
        LlmClient llm = llmClients.get(task.lane);
        EmbeddingClient embed = embeddingClients.get(task.lane);
        try {
            task.run(llm, embed);
        } catch (RuntimeException e) {
            // task.run内部で基本的に例外は握りつぶす設計だが、万一のための保険。
            logger.warning("LLMタスクの実行中に予期しない例外が発生しました (lane=" + task.lane + "): " + e.getMessage());
            task.completeExceptionallySafely(e);
        }
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
        worker.interrupt();
    }

    // ═══════════════════════════════════════
    //  内部タスク表現
    // ═══════════════════════════════════════

    private abstract static class Task<T> implements Comparable<Task<?>> {
        final LlmLane lane;
        final long sequence;
        final CompletableFuture<T> future = new CompletableFuture<>();

        Task(LlmLane lane, long sequence) {
            this.lane = lane;
            this.sequence = sequence;
        }

        /** 実際にLlmClient/EmbeddingClientを呼び出す。例外は内部で握りつぶし、失敗時はfutureをnullで完了させる。 */
        abstract void run(LlmClient llm, EmbeddingClient embed);

        void completeExceptionallySafely(Throwable t) {
            future.complete(null);
        }

        @Override
        public int compareTo(Task<?> other) {
            int cmp = Integer.compare(this.lane.rank(), other.lane.rank());
            if (cmp != 0) return cmp;
            return Long.compare(this.sequence, other.sequence); // 同レーン内は先着順（FIFO）
        }
    }

    private static final class CallTask extends Task<String> {
        private final String systemPrompt;
        private final String userPrompt;
        private final int maxTokens;
        private final double temperature;

        CallTask(LlmLane lane, long sequence, String systemPrompt, String userPrompt, int maxTokens, double temperature) {
            super(lane, sequence);
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
        }

        @Override
        void run(LlmClient llm, EmbeddingClient embed) {
            try {
                future.complete(llm.call(systemPrompt, userPrompt, maxTokens, temperature));
            } catch (RuntimeException e) {
                logger.warning("LLM呼び出し(call)に失敗しました (lane=" + lane + "): " + e.getMessage());
                future.complete(null);
            }
        }
    }

    private static final class EmbedTask extends Task<float[]> {
        private final String text;

        EmbedTask(LlmLane lane, long sequence, String text) {
            super(lane, sequence);
            this.text = text;
        }

        @Override
        void run(LlmClient llm, EmbeddingClient embed) {
            try {
                future.complete(embed.embed(text));
            } catch (RuntimeException e) {
                logger.warning("LLM呼び出し(embed)に失敗しました (lane=" + lane + "): " + e.getMessage());
                future.complete(null);
            }
        }
    }
}
