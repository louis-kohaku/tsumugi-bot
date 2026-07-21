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
 * 退会機能追加に伴い、紬希が管理するシステム専用チャンネル（🌼｜から始まるチャンネル：
 * 入室・退会・管理者通知など）は通常会話として扱わないようにしている
 * （実処理はInitialSetupListener/WithdrawalListener側が担当）。
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

    private final ConversationEngine conversationEngine;
    private final EpisodicEventRepository episodicEventRepository;
    private final EvidenceExtractor evidenceExtractor;
    private final DataSubjectRightsService dataSubjectRightsService;
    private final ExecutorService workerPool = Executors.newFixedThreadPool(4);

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

    // 「🌼｜」で始まるチャンネル（入室・退会・管理者通知など、紬希が作るシステム専用チャンネル）は
    // 通常会話としては扱わない。実際の処理はInitialSetupListener/WithdrawalListener側が担当する。
    // 「紬希の庭」配下の雑談部屋等にはこのプレフィックスが付かないため、通常通り会話処理される。
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

            String reply = conversationEngine.generateReply(userId, text);
            if (reply == null || reply.isBlank()) {
                reply = FALLBACK_REPLY;
            }
            channel.sendMessage(reply).queue();

            EpisodicEvent aiEvent = new EpisodicEvent(
                    userId, ChannelType.NORMAL_CHAT, reply, Speaker.AI, LocalDate.now(), null);
            episodicEventRepository.save(aiEvent);

            // Evidence抽出・UserModelへの反映は応答速度に影響させたくないので非同期のまま続行する
            try {
                evidenceExtractor.extractAndConsolidate(userId, userEvent.id, text);
            } catch (RuntimeException e) {
                logger.warning("Evidence抽出処理で例外が発生しました (userId=" + userId + "): " + e.getMessage());
            }
        } catch (RuntimeException e) {
            logger.warning("メッセージ処理中にエラーが発生しました (userId=" + userId + "): " + e.getMessage());
            channel.sendMessage("エラーが発生しました。少し時間をおいて、もう一度お試しください。").queue();
        }
    }

    public void shutdown() {
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
