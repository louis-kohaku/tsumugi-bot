package tsumugi.diary;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.diary.model.DiaryQueueEntry;
import tsumugi.diary.store.DiaryQueueRepository;
import tsumugi.diary.store.DiaryRepository;
import tsumugi.diary.store.sqlite.SqliteDiaryQueueRepository;
import tsumugi.diary.store.sqlite.SqliteDiaryRepository;
import tsumugi.initialsetup.InitialSetupState;
import tsumugi.initialsetup.model.InitialSetupRecord;
import tsumugi.initialsetup.store.InitialSetupRepository;
import tsumugi.llm.LlmClient;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;

import java.util.logging.Logger;

/**
 * 日記機能全体の組み立て・エントリーポイントを担うファサード。
 *
 * 【変更点】日記部屋へのアクセス集中対策として、セッション完了（最後の質問への回答）を
 * 受けた時点では要約生成をその場で行わず、diary_queue（SQL永続キュー）へ積むだけにした。
 * インメモリのDiarySessionはここで即座に破棄し、待機中のデータはSQLにのみ保持する。
 * 実際の生成処理はDiaryQueueWorkerがバックグラウンドで順番に行う。
 *
 * 要望部屋の作成は2経路ある（変更なし）:
 *  1. 新規ユーザーの名前確定時: InitialSetupServiceのonDisplayNameConfirmedコールバック経由
 *  2. 既存ユーザー分のバックフィル: bootstrapGuilds()で起動時にinitial_setupテーブルを走査
 */
public final class DiaryManager {

    private static final Logger logger = Logger.getLogger(DiaryManager.class.getName());

    private final DiarySessionManager sessionManager;
    private final DiaryChannelService channelService;
    private final DiaryQueueRepository queueRepository;
    private final DiaryQueueWorker queueWorker;
    private final InitialSetupRepository initialSetupRepository;

    public DiaryManager(DiarySessionManager sessionManager,
                         DiaryChannelService channelService,
                         DiaryQueueRepository queueRepository,
                         DiaryQueueWorker queueWorker,
                         InitialSetupRepository initialSetupRepository) {
        this.sessionManager = sessionManager;
        this.channelService = channelService;
        this.queueRepository = queueRepository;
        this.queueWorker = queueWorker;
        this.initialSetupRepository = initialSetupRepository;
    }

    /**
     * @param diaryLlmClient 日記の総評生成に使うLlmClient。
     *                       呼び出し側（TsumugiApplication）で LlmLane.DIARY に紐づいた
     *                       LaneLlmClient を渡すこと。これにより「一度生成が始まったら
     *                       会話が来ても完了まで中断しない」という挙動がLaneLlmDispatcher側で保証される。
     */
    public static DiaryManager createDefault(SqliteConnectionFactory connectionFactory,
                                               LlmClient diaryLlmClient,
                                               InitialSetupRepository initialSetupRepository) {
        DiaryRepository repository = new SqliteDiaryRepository(connectionFactory);
        DiaryQueueRepository queueRepository = new SqliteDiaryQueueRepository(connectionFactory);
        DiarySummaryGenerator summaryGenerator = new DiarySummaryGenerator(diaryLlmClient);
        DiaryService service = new DiaryService(repository, summaryGenerator);
        DiaryChannelService channelService = new DiaryChannelService();
        DiaryQueueWorker queueWorker = new DiaryQueueWorker(queueRepository, service, channelService);

        return new DiaryManager(new DiarySessionManager(), channelService, queueRepository, queueWorker, initialSetupRepository);
    }

    /**
     * Bot起動完了直後に呼ぶ。以下をまとめて保証する。
     *  1. 参加している全ギルドに「🌼｜要望」カテゴリが存在すること
     *  2. 既存ユーザー分の要望部屋バックフィル
     *  3. diary_queueワーカーの起動（再起動をまたいだPROCESSING行の復旧を含む）
     */
    public void bootstrapGuilds(JDA jda) {
        for (Guild guild : jda.getGuilds()) {
            try {
                channelService.ensureRequestCategory(guild);
            } catch (RuntimeException e) {
                logger.warning("要望カテゴリの保証に失敗しました (guildId=" + guild.getIdLong() + "): " + e.getMessage());
            }
        }

        for (InitialSetupRecord record : initialSetupRepository.loadByState(InitialSetupState.COMPLETED)) {
            if (record.displayName == null || record.displayName.isBlank()) continue;

            Guild guild = jda.getGuildById(record.guildId);
            if (guild == null) continue;

            Member member = guild.getMemberById(record.userId);
            if (member == null) continue; // 既に離脱済み等

            try {
                channelService.ensureRequestRoom(guild, member, record.displayName);
            } catch (RuntimeException e) {
                logger.warning("要望部屋のバックフィルに失敗しました (userId=" + record.userId + "): " + e.getMessage());
            }
        }
        logger.info("日記機能: 既存ユーザーの要望部屋バックフィルが完了しました。");

        queueWorker.setJda(jda);
        queueWorker.start();
    }

    public void onMemberDisplayNameConfirmed(Member member) {
        try {
            channelService.ensureRequestRoom(member.getGuild(), member, member.getEffectiveName());
        } catch (RuntimeException e) {
            logger.warning("要望部屋の作成に失敗しました (userId=" + member.getIdLong() + "): " + e.getMessage());
        }
    }

    /** /日記コマンド実行時にDiaryListenerから呼ばれる。 */
    public void handleDiaryCommand(Member member, TextChannel requestRoom) {
        long userId = member.getIdLong();
        if (sessionManager.hasActiveSession(userId)) {
            channelService.postMessage(requestRoom, "既に日記セッションが進行中です。先にそちらを完了してください。");
            return;
        }

        String displayName = member.getEffectiveName();
        TextChannel diaryRoom = channelService.createDiaryRoom(member.getGuild(), member, displayName);

        DiarySession session = sessionManager.startSession(userId, diaryRoom.getIdLong(), DiaryMode.STANDARD);
        channelService.postMessage(diaryRoom, sessionManager.currentPrompt(session));
    }

    /**
     * プライベート日記部屋への投稿を受け取ったときにDiaryListenerから呼ばれる。
     *
     * 最後の質問（明日挑戦すること）への回答を受けてGENERATING_SUMMARY状態になった時点で、
     * 要約生成はその場では行わず、DiaryQueueEntryを組み立ててdiary_queue（SQL）へ積むだけにする。
     * インメモリのセッションはここで破棄する（待機データをメモリに残さないため）。
     * 実際の生成はDiaryQueueWorkerが順番にLlmLane.DIARY経由で処理し、完了後にこのチャンネルへ投稿する。
     */
    public void handleDiaryRoomMessage(Member member, String rawText, TextChannel channel) {
        long userId = member.getIdLong();
        DiarySession session = sessionManager.getSession(userId);
        if (session == null || session.channelId != channel.getIdLong()) return;

        try {
            String nextPrompt = sessionManager.handleInput(session, rawText);

            if (session.state == DiaryState.GENERATING_SUMMARY) {
                DiaryQueueEntry entry = DiaryQueueEntry.fromSession(session, member.getGuild().getIdLong());
                queueRepository.enqueue(entry);
                channelService.postMessage(channel, DiaryWaitingMessages.random());
                sessionManager.endSession(userId);
                return;
            }

            if (nextPrompt != null) {
                channelService.postMessage(channel, nextPrompt);
            }
        } catch (RuntimeException e) {
            logger.warning("日記セッション処理に失敗しました (userId=" + userId + "): " + e.getMessage());
            channelService.postMessage(channel, "エラーが発生しました。少し時間をおいて、もう一度お試しください。");
        }
    }

    /** /日記が要望部屋以外で実行された場合のフォールバック用に、対象ユーザーの要望部屋を返す。 */
    public TextChannel getOrCreateRequestRoom(Member member) {
        return channelService.ensureRequestRoom(member.getGuild(), member, member.getEffectiveName());
    }

    public boolean isRequestRoomName(String channelName) {
        return channelService.isRequestRoomName(channelName);
    }

    public boolean isDiaryRoomName(String channelName) {
        return channelService.isDiaryRoomName(channelName);
    }

    public void shutdown() {
        channelService.shutdown();
        queueWorker.shutdown();
    }
}
