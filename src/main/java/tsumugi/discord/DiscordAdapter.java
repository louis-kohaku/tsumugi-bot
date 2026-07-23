package tsumugi.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import tsumugi.conversation.ConversationEngine;
import tsumugi.core.model.TsumugiModel.ChannelType;
import tsumugi.core.model.TsumugiModel.EpisodicEvent;
import tsumugi.core.model.TsumugiModel.Speaker;
import tsumugi.memory.extract.EvidenceExtractor;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.memory.store.EpisodicEventRepository;

import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Discord疎通層。JDAのイベントを受けて、記憶層・会話エンジンに橋渡しする。
 * すみれ/紬希のコアロジックはDiscordに一切依存しないよう、このクラスだけがJDAを知っている。
 *
 * 利用規約第12条（削除権）・AI利用者権利章典第4条（忘れられる権利）に対応するため、
 * 「忘れて」を含む発話をトリガーとしてDataSubjectRightsService.forgetUser()を呼び出す。
 * ※簡易な文字列一致によるトリガーであり、誤爆防止のため確認メッセージを挟む2段階方式とする。
 *
 * 【変更点】Evidence抽出（性格・感情等の分析）は、以前は応答生成の直後に同期で実行していたが、
 * これをやめて「応答送信後、30秒後にEvidence抽出用の投入専用スレッドへ回す」方式に変更した。
 * 理由:
 *  - Evidence抽出はLlmLane.HEAVYで実行され、LaneLlmDispatcher側で「会話が一定時間アイドルに
 *    なるまで着手しない」よう制御されている。会話直後にworkerPoolのスレッドで同期的に
 *    呼び出すと、そのスレッドがHEAVYレーンの順番待ちで長時間ブロックされ、
 *    workerPool（4並列）の枯渇につながりうる。
 *  - 30秒の遅延自体が「実行開始のタイミングを後ろにずらす」ための間引きにもなる。
 * 投入は専用の単一スレッド（evidenceScheduler）で行うため、ここが仮にHEAVYレーンの
 * 順番待ちでブロックされても、会話応答用のworkerPoolには一切影響しない。
 */
public final class DiscordAdapter extends ListenerAdapter {

    private static final Logger logger = Logger.getLogger(DiscordAdapter.class.getName());
    private static final String FALLBACK_REPLY = "うまく応答を作れませんでした…もう一度お話しいただけますか？";

    private static final String FORGET_TRIGGER = "忘れて";
    private static final String FORGET_CONFIRM_KEYWORD = "本当に忘れて";
    private static final String FORGET_CONFIRM_PROMPT =
            "これまでの会話・記憶（AIプロフィールを含みます）を全て削除します。" +
            "この操作は元に戻せません。本当によろしければ「本当に忘れて」とお送りください。";
    private static final String FORGET_DONE_REPLY =
            "これまでの記憶を削除しました。次にお話しするときは、また一からよろしくお願いします。";

    /** Evidence抽出を応答送信からどれだけ遅らせて投入するか。 */
    private static final long EVIDENCE_EXTRACT_DELAY_SECONDS = 30;

    private final ConversationEngine conversationEngine;
    private final EpisodicEventRepository episodicEventRepository;
    private final EvidenceExtractor evidenceExtractor;
    private final DataSubjectRightsService dataSubjectRightsService;
    private final ExecutorService workerPool = Executors.newFixedThreadPool(4);

    /** Evidence抽出の「投入」だけを行う専用スレッド。ここがHEAVYレーンの順番待ちでブロックされても会話には影響しない。 */
    private final ScheduledExecutorService evidenceScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "evidence-extract-scheduler");
                t.setDaemon(true);
                return t;
            });

    public DiscordAdapter(ConversationEngine conversationEngine,
                           EpisodicEventRepository episodicEventRepository,
                           EvidenceExtractor evidenceExtractor,
                           DataSubjectRightsService dataSubjectRightsService) {
        this.conversationEngine = conversationEngine;
        this.episodicEventRepository = episodicEventRepository;
        this.evidenceExtractor = evidenceExtractor;
        this.dataSubjectRightsService = dataSubjectRightsService;
    }

    /** JDAを起動し、このアダプタをリスナーとして登録する。呼び出し側がJDAのライフサイクルを管理する。 */
    public static JDA start(String token, DiscordAdapter adapter) throws InterruptedException {
        return start(token, adapter, new Object[0]);
    }

    /**
     * JDAを起動し、このアダプタに加えて追加のリスナー（例: InitialSetupListener, WithdrawalListener）も登録する。
     * GUILD_MEMBERSインテントは初期設定フロー（GuildMemberJoinEvent検知）に必要なため、ここで有効化する。
     */
    public static JDA start(String token, DiscordAdapter adapter, Object... additionalListeners) throws InterruptedException {
        JDABuilder builder = JDABuilder.createDefault(token)
                .enableIntents(
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.DIRECT_MESSAGES,
                        GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(adapter);
        if (additionalListeners.length > 0) {
            builder.addEventListeners(additionalListeners);
        }
        JDA jda = builder.build();
        jda.awaitReady();
        return jda;
    }

    // 「🌼｜」で始まるチャンネル（入室・退会・管理者通知・要望・日記など、紬希が作るシステム専用チャンネル）は
    // 通常会話としては扱わない。実際の処理は各機能のListener側が担当する。
    private static final String SYSTEM_CHANNEL_PREFIX = "🌼｜";

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (event.getChannel().getName().startsWith(SYSTEM_CHANNEL_PREFIX)) return;

        String text = event.getMessage().getContentDisplay();
        if (text == null || text.isBlank()) return;

        long userId = event.getAuthor().getIdLong();
        MessageChannel channel = event.getChannel();

        workerPool.submit(() -> handle(userId, text, channel));
    }

    private void handle(long userId, String text, MessageChannel channel) {
        try {
            // 削除権（忘れられる権利）対応: 確定ワードを含む場合は即削除して通常の会話処理をスキップする
            if (text.contains(FORGET_CONFIRM_KEYWORD)) {
                dataSubjectRightsService.forgetUser(userId);
                channel.sendMessage(FORGET_DONE_REPLY).queue();
                return;
            }
            // 「忘れて」を含むが確定ワードでない場合は、誤操作防止のため確認を挟む
            if (text.contains(FORGET_TRIGGER)) {
                channel.sendMessage(FORGET_CONFIRM_PROMPT).queue();
                return;
            }

            EpisodicEvent userEvent = new EpisodicEvent(
                    userId, ChannelType.NORMAL_CHAT, text, Speaker.USER, LocalDate.now(), null);
            episodicEventRepository.save(userEvent);

            // ここでLlmLane.CHATを通じてLM Studioへ問い合わせる。
            // このスレッドはこの呼び出しが完了するまでブロックされるが、
            // LaneLlmDispatcher側では常にCHATが最優先で選ばれるため、
            // 日記(DIARY)が実行中でなければ即座に処理される。
            String reply = conversationEngine.generateReply(userId, text);
            if (reply == null || reply.isBlank()) {
                reply = FALLBACK_REPLY;
            }
            channel.sendMessage(reply).queue();

            EpisodicEvent aiEvent = new EpisodicEvent(
                    userId, ChannelType.NORMAL_CHAT, reply, Speaker.AI, LocalDate.now(), null);
            episodicEventRepository.save(aiEvent);

            // Evidence抽出（HEAVYレーン）はここでは実行しない。
            // 30秒後に専用スケジューラへ投入するだけにして、会話応答用のworkerPoolスレッドを
            // すぐに解放する（HEAVYレーンの順番待ちで会話処理が詰まるのを防ぐため）。
            String userTextForExtraction = text;
            String sourceEventId = userEvent.id;
            evidenceScheduler.schedule(() -> {
                try {
                    evidenceExtractor.extractAndConsolidate(userId, sourceEventId, userTextForExtraction);
                } catch (RuntimeException e) {
                    logger.warning("Evidence抽出処理で例外が発生しました (userId=" + userId + "): " + e.getMessage());
                }
            }, EVIDENCE_EXTRACT_DELAY_SECONDS, TimeUnit.SECONDS);

        } catch (RuntimeException e) {
            logger.warning("メッセージ処理中にエラーが発生しました (userId=" + userId + "): " + e.getMessage());
            channel.sendMessage("エラーが発生しました。少し時間をおいて、もう一度お試しください。").queue();
        }
    }

    public void shutdown() {
        workerPool.shutdown();
        evidenceScheduler.shutdown();
        try {
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
            if (!evidenceScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                evidenceScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            evidenceScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
