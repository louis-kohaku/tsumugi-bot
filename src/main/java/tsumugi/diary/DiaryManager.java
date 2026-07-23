package tsumugi.diary;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import tsumugi.diary.model.DiaryRecord;
import tsumugi.diary.store.DiaryRepository;
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
 * 要望部屋の作成は2経路ある:
 *  1. 新規ユーザーの名前確定時: InitialSetupServiceのonDisplayNameConfirmedコールバック経由
 *     （TsumugiApplication側で
 *       initialSetupManager.getService().setOnDisplayNameConfirmed(diaryManager::onMemberDisplayNameConfirmed)
 *       と配線する。詳細はCHANGES_TO_EXISTING_FILES.md参照）
 *  2. 既存ユーザー分のバックフィル: bootstrapGuilds()で起動時に
 *     initial_setup テーブル（COMPLETED状態＝表示名登録済み）を走査し、未作成なら作成する
 *
 * InitialSetupRepositoryは読み取り専用の参照として利用するのみで、書き込みは行わない。
 */
public final class DiaryManager {

    private static final Logger logger = Logger.getLogger(DiaryManager.class.getName());

    private final DiarySessionManager sessionManager;
    private final DiaryChannelService channelService;
    private final DiaryService service;
    private final InitialSetupRepository initialSetupRepository;

    public DiaryManager(DiarySessionManager sessionManager,
                         DiaryChannelService channelService,
                         DiaryService service,
                         InitialSetupRepository initialSetupRepository) {
        this.sessionManager = sessionManager;
        this.channelService = channelService;
        this.service = service;
        this.initialSetupRepository = initialSetupRepository;
    }

    public static DiaryManager createDefault(SqliteConnectionFactory connectionFactory,
                                               LlmClient llmClient,
                                               InitialSetupRepository initialSetupRepository) {
        DiaryRepository repository = new SqliteDiaryRepository(connectionFactory);
        DiarySummaryGenerator summaryGenerator = new DiarySummaryGenerator(llmClient);
        DiaryService service = new DiaryService(repository, summaryGenerator);
        return new DiaryManager(new DiarySessionManager(), new DiaryChannelService(), service, initialSetupRepository);
    }

    /**
     * Bot起動完了直後に呼ぶ。
     * 1. 参加している全ギルドに「🌼｜要望」カテゴリが存在することを保証する。
     * 2. 既に名前登録済み（InitialSetupState.COMPLETED）の全ユーザーについて、
     *    要望部屋が無ければ作成する（Bot導入前から使っていた既存ユーザーのバックフィル）。
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
    }

    /**
     * InitialSetupService.onDisplayNameConfirmedから呼ばれる想定のコールバック。
     * 新規入室・引継ぎ確認完了の両方で、名前確定のたびに呼ばれる（冪等なので何度呼んでも安全）。
     */
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

    /** プライベート日記部屋への投稿を受け取ったときにDiaryListenerから呼ばれる。 */
    public void handleDiaryRoomMessage(Member member, String rawText, TextChannel channel) {
        long userId = member.getIdLong();
        DiarySession session = sessionManager.getSession(userId);
        if (session == null || session.channelId != channel.getIdLong()) return;

        try {
            String nextPrompt = sessionManager.handleInput(session, rawText);

            if (session.state == DiaryState.GENERATING_SUMMARY) {
                DiaryRecord record = service.completeSession(session);
                channelService.postMessage(channel, record.dailySummary);
                channelService.postMessage(channel, "この日記部屋は1分後に削除されます😊");
                channelService.scheduleDiaryRoomDelete(channel);
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
    }
}
