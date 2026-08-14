package tsumugi.app;

import net.dv8tion.jda.api.JDA;
import okhttp3.OkHttpClient;
import tsumugi.broadcast.BroadcastListener;
import tsumugi.broadcast.BroadcastManager;
import tsumugi.conversation.ConversationEngine;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.EvidenceCategory;
import tsumugi.core.model.TsumugiModel.Polarity;
import tsumugi.discord.DiscordAdapter;
import tsumugi.forcedwithdrawal.ForcedWithdrawalListener;
import tsumugi.forcedwithdrawal.ForcedWithdrawalManager;
import tsumugi.initialsetup.InitialSetupListener;
import tsumugi.initialsetup.InitialSetupManager;
import tsumugi.llm.EmbeddingClient;
import tsumugi.llm.LaneEmbeddingClient;
import tsumugi.llm.LaneLlmClient;
import tsumugi.llm.LaneLlmDispatcher;
import tsumugi.llm.LlmClient;
import tsumugi.llm.LlmLane;
import tsumugi.llm.LmStudioGateway;
import tsumugi.memory.anonymized.AnonymizedDataRepository;
import tsumugi.memory.anonymized.SqliteAnonymizedDataRepository;
import tsumugi.memory.consolidate.MemoryConsolidator;
import tsumugi.memory.extract.EvidenceExtractor;
import tsumugi.memory.retrieval.MemoryRetriever;
import tsumugi.memory.retrieval.RetrievalResult;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;
import tsumugi.memory.store.sqlite.SqliteEpisodicEventRepository;
import tsumugi.memory.store.sqlite.SqliteEvidenceRepository;
import tsumugi.memory.store.sqlite.SqliteUserModelRepository;
import tsumugi.memory.store.sqlite.UserConnectionFactoryRegistry;
import tsumugi.memory.store.sqlite.UserDbFolderRepository;
import tsumugi.withdrawal.WithdrawalListener;
import tsumugi.withdrawal.WithdrawalManager;
import tsumugi.withdrawal.store.WithdrawalRepository;
import tsumugi.withdrawal.store.sqlite.SqliteWithdrawalRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 紬希の起動エントリーポイント。
 *
 * 【今回の変更点: 初期設定未完了ユーザーの救済（バックフィル）＋通常会話のブロック】
 * Bot導入前から在籍していた等の理由で、実際には会話履歴があるのに
 * initial_setupがCOMPLETEDまで進んでいないユーザーが存在し、お知らせ配信
 * （InitialSetupState.COMPLETEDでフィルタ）が届かない・庭が無い、という不整合が
 * 起きていた問題への対応として、以下の2点を追加した。
 *
 *   1. DiscordAdapterに InitialSetupRepository を注入し、ギルド内のメッセージについては
 *      送信者のInitialSetupStateがCOMPLETEDでない限り通常会話をブロックし、初期設定を
 *      促す案内を返すようにした（DiscordAdapterのコンストラクタ引数が1つ増えている）。
 *   2. InitialSetupManager.bootstrapGuilds(jda) の直後に
 *      initialSetupManager.runLegacyBackfill(jda, episodicEventRepository) を呼び、
 *      「初期設定がCOMPLETED/WAITING_NAMEより先に進んでいない」かつ「過去に発話履歴がある」
 *      ユーザーを検出して、通常の利用規約同意フローに自動的に乗せるようにした
 *      （同意の取得自体は省略しない）。
 *
 * 【既存: LLM最大トークン数の外部設定化】
 * これまで各クラス（ConversationEngine / EvidenceExtractor / DiarySummaryGenerator /
 * BroadcastReviewer）にハードコードされていたLLM呼び出しの最大トークン数（max_tokens）を、
 * AppConfig経由で .env から読み込む形に変更している。
 *
 *   LLM_MAX_TOKENS_CHAT       … 通常会話（デフォルト800）
 *   LLM_MAX_TOKENS_EVIDENCE   … Evidence抽出（デフォルト800）
 *   LLM_MAX_TOKENS_DIARY      … 日記総評生成（デフォルト600）
 *   LLM_MAX_TOKENS_BROADCAST  … お知らせ文校正（デフォルト800）
 *
 * 【既存: 強制退会機能の配線】
 * 管理者が対象者を選んで強制的に退会させる tsumugi.forcedwithdrawal パッケージを配線している。
 * InitialSetupManagerが既に持っている共有DB用InitialSetupRepository・InitialSetupChannelService、
 * および記憶層のEvidenceRepository・AnonymizedDataRepository・DataSubjectRightsServiceを
 * そのまま共有して組み立てる。
 *
 * 【既存: お知らせ配信機能】
 * Kohaku専用の「🌼｜お知らせ配信」チャンネルに投稿した内容をLLMで校正チェックし、
 * 確認（はい/いいえ、往復修正可）のうえで初期設定完了済み全ユーザーの
 * お知らせ部屋（announceChannelId）へ一斉配信する機能。
 * LLM呼び出しはLlmLane.BROADCAST（CHATと同格の即時レーン、重量モデル使用）経由で行う。
 *
 * 【既存: LM Studioへの問い合わせを1本のディスパッチャに集約】
 * 会話応答・Evidence抽出・日記の総評生成・お知らせ文チェックのLM Studio呼び出しは全て
 * LaneLlmDispatcher（ワーカースレッド1本）経由に集約されている。詳細はLaneLlmDispatcher参照。
 *
 * DB構成（変更なし）:
 *  - 共有DB（config.dbPath）: initial_setup / withdrawal / membership_events /
 *    user_db_folders / diary_records / diary_queue / anonymized_evidence /
 *    broadcast_history / forced_withdrawal など。
 *  - ユーザーごとDB（config.userDbDir/{表示名+登録日時}/tsumugi.db）: episodic_events /
 *    evidence / evidence_vec / user_model など。
 */
public final class TsumugiApplication {

    private static final Logger logger = Logger.getLogger(TsumugiApplication.class.getName());

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        logger.info("紬希を起動します...");
        logger.info("LLM最大トークン数設定: CHAT=" + config.llmMaxTokensChat
                + " EVIDENCE=" + config.llmMaxTokensEvidence
                + " DIARY=" + config.llmMaxTokensDiary
                + " BROADCAST=" + config.llmMaxTokensBroadcast);

        // ── 共有DB（運用管理データ）のセットアップ ──────────
        Path sharedDbPath = Paths.get(config.dbPath);
        if (sharedDbPath.getParent() != null) {
            Files.createDirectories(sharedDbPath.getParent());
        }
        SqliteConnectionFactory sharedConnectionFactory =
                new SqliteConnectionFactory(sharedDbPath, config.sqliteVecExtensionPath);
        sharedConnectionFactory.open().close();
        logger.info("共有DB接続OK: " + sharedDbPath);

        // ── ユーザーごとDB（記憶層）のセットアップ ────────────
        Path userDbDir = Paths.get(config.userDbDir);
        Files.createDirectories(userDbDir);
        UserDbFolderRepository userDbFolderRepository = new UserDbFolderRepository(sharedConnectionFactory);
        UserConnectionFactoryRegistry userDbRegistry =
                new UserConnectionFactoryRegistry(userDbDir, config.sqliteVecExtensionPath, userDbFolderRepository);
        logger.info("ユーザーごとDBのベースディレクトリ: " + userDbDir);

        SqliteEvidenceRepository evidenceRepository = new SqliteEvidenceRepository(userDbRegistry);
        SqliteUserModelRepository userModelRepository = new SqliteUserModelRepository(userDbRegistry);
        SqliteEpisodicEventRepository episodicEventRepository = new SqliteEpisodicEventRepository(userDbRegistry);

        DataSubjectRightsService dataSubjectRightsService = new DataSubjectRightsService(
                episodicEventRepository, evidenceRepository, userModelRepository);

        // 退会時「匿名化して保存」・再入室時「引継ぎ辞退（匿名化保存）」・強制退会の
        // いずれでも使う匿名データの保存先。個人と紐付く情報は一切保存しない
        // （AnonymizedDataRepository参照）。
        AnonymizedDataRepository anonymizedDataRepository = new SqliteAnonymizedDataRepository(sharedConnectionFactory);

        // ── 退会機能のRepositoryを先に生成（初期設定側のMembershipManager/InitialSetupServiceと共有するため） ──
        WithdrawalRepository withdrawalRepository = new SqliteWithdrawalRepository(sharedConnectionFactory);

        InitialSetupManager initialSetupManager = InitialSetupManager.createDefault(
                sharedConnectionFactory,
                withdrawalRepository,
                userDbRegistry,
                dataSubjectRightsService,
                evidenceRepository,
                anonymizedDataRepository);
        InitialSetupListener initialSetupListener = new InitialSetupListener(initialSetupManager);

        // DiscordAdapterの会話ゲート（初期設定未完了ユーザーのブロック）に使う。
        // initialSetupManager内部のRepositoryとは別インスタンスだが、同じ共有DB・同じテーブルを
        // 指すため実質的に同じ状態を参照する（他のRepository群と同じ方針。SqliteInitialSetupRepository参照）。
        tsumugi.initialsetup.store.InitialSetupRepository initialSetupRepositoryForChatGate =
                new tsumugi.initialsetup.store.sqlite.SqliteInitialSetupRepository(sharedConnectionFactory);

        // ── LLM連携層のセットアップ ────────────────────
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .build();

        // 重量モデルが未設定の場合は、通常会話用モデルにフォールバックする
        // （設定ミスで即座に起動失敗にはせず、レーン分離自体は機能させる）。
        String heavyModel = config.lmStudioHeavyModel.isBlank() ? config.lmStudioChatModel : config.lmStudioHeavyModel;
        if (config.lmStudioHeavyModel.isBlank()) {
            logger.warning("LM_STUDIO_HEAVY_MODELが未設定のため、日記・Evidence抽出・お知らせ校正も会話用モデルを使用します。");
        }

        LmStudioGateway chatGateway = new LmStudioGateway(
                config.lmStudioBaseUrl, config.lmStudioChatModel, config.lmStudioEmbeddingModel, httpClient);
        LmStudioGateway heavyGateway = new LmStudioGateway(
                config.lmStudioBaseUrl, heavyModel, config.lmStudioEmbeddingModel, httpClient);

        // CHAT=軽量・即時最優先 / BROADCAST=重量・CHATと同格の即時 / DIARY=重量・CHATと同格の即時 / HEAVY=重量・アイドル時のみ
        Map<LlmLane, LlmClient> llmClientsByLane = new EnumMap<>(LlmLane.class);
        llmClientsByLane.put(LlmLane.CHAT, chatGateway);
        llmClientsByLane.put(LlmLane.BROADCAST, heavyGateway);
        llmClientsByLane.put(LlmLane.DIARY, heavyGateway);
        llmClientsByLane.put(LlmLane.HEAVY, heavyGateway);

        Map<LlmLane, EmbeddingClient> embeddingClientsByLane = new EnumMap<>(LlmLane.class);
        embeddingClientsByLane.put(LlmLane.CHAT, chatGateway);
        embeddingClientsByLane.put(LlmLane.BROADCAST, heavyGateway);
        embeddingClientsByLane.put(LlmLane.DIARY, heavyGateway);
        embeddingClientsByLane.put(LlmLane.HEAVY, heavyGateway);

        LaneLlmDispatcher dispatcher = new LaneLlmDispatcher(llmClientsByLane, embeddingClientsByLane);

        LlmClient chatLlm = new LaneLlmClient(dispatcher, LlmLane.CHAT);
        LaneEmbeddingClient chatEmbed = new LaneEmbeddingClient(dispatcher, LlmLane.CHAT);
        LlmClient broadcastLlm = new LaneLlmClient(dispatcher, LlmLane.BROADCAST);
        LlmClient diaryLlm = new LaneLlmClient(dispatcher, LlmLane.DIARY);
        LlmClient heavyLlm = new LaneLlmClient(dispatcher, LlmLane.HEAVY);
        LaneEmbeddingClient heavyEmbed = new LaneEmbeddingClient(dispatcher, LlmLane.HEAVY);

        // ── 退会フローのセットアップ（初期設定と同じ管理者ログチャンネルを使い回す） ──
        WithdrawalManager withdrawalManager = WithdrawalManager.createDefault(
                withdrawalRepository, dataSubjectRightsService, initialSetupManager.getChannelService());
        WithdrawalListener withdrawalListener = new WithdrawalListener(withdrawalManager);

        // ── 記憶層: 会話中の検索はCHATレーン、Evidence保存時のembeddingはHEAVYレーン ──
        MemoryConsolidator consolidator = new MemoryConsolidator(userModelRepository, evidenceRepository, heavyEmbed);
        MemoryRetriever retriever = new MemoryRetriever(evidenceRepository, chatEmbed);

        if (config.lmStudioChatModel.isBlank()) {
            logger.warning(".envにLM_STUDIO_CHAT_MODELが未設定のため、LLM疎通テストをスキップします。");
        } else {
            runSmokeTest(consolidator, retriever);
        }

        // ── 日記機能のセットアップ（要約生成はDIARYレーン、トークン数はconfig.llmMaxTokensDiary） ──────────
        tsumugi.initialsetup.store.InitialSetupRepository initialSetupRepositoryForDiary =
                new tsumugi.initialsetup.store.sqlite.SqliteInitialSetupRepository(sharedConnectionFactory);
        tsumugi.diary.DiaryManager diaryManager = tsumugi.diary.DiaryManager.createDefault(
                sharedConnectionFactory, diaryLlm, initialSetupRepositoryForDiary, config.llmMaxTokensDiary);
        tsumugi.diary.DiaryListener diaryListener = new tsumugi.diary.DiaryListener(diaryManager);

        initialSetupManager.getService().setOnDisplayNameConfirmed(diaryManager::onMemberDisplayNameConfirmed);

        // ── お知らせ配信機能のセットアップ（校正チェックはBROADCASTレーン、トークン数はconfig.llmMaxTokensBroadcast） ──────────
        tsumugi.initialsetup.store.InitialSetupRepository initialSetupRepositoryForBroadcast =
                new tsumugi.initialsetup.store.sqlite.SqliteInitialSetupRepository(sharedConnectionFactory);
        BroadcastManager broadcastManager = BroadcastManager.createDefault(
                sharedConnectionFactory, broadcastLlm, initialSetupRepositoryForBroadcast, config.llmMaxTokensBroadcast);
        BroadcastListener broadcastListener = new BroadcastListener(broadcastManager);

        // ── 強制退会機能のセットアップ ────────────────────
        // 初期設定側と同じ共有DB用InitialSetupRepository・InitialSetupChannelService、
        // 記憶層のEvidenceRepository・AnonymizedDataRepository・DataSubjectRightsServiceを
        // そのまま共有する（退会フローと同じ方針）。
        tsumugi.initialsetup.store.InitialSetupRepository initialSetupRepositoryForForcedWithdrawal =
                new tsumugi.initialsetup.store.sqlite.SqliteInitialSetupRepository(sharedConnectionFactory);
        ForcedWithdrawalManager forcedWithdrawalManager = ForcedWithdrawalManager.createDefault(
                sharedConnectionFactory,
                initialSetupRepositoryForForcedWithdrawal,
                initialSetupManager.getChannelService(),
                evidenceRepository,
                anonymizedDataRepository,
                dataSubjectRightsService);
        ForcedWithdrawalListener forcedWithdrawalListener = new ForcedWithdrawalListener(forcedWithdrawalManager);

        // ── 会話エンジン（CHATレーン、トークン数はconfig.llmMaxTokensChat）
        //    ・Evidence抽出層（HEAVYレーン、トークン数はconfig.llmMaxTokensEvidence）のセットアップ ──
        ConversationEngine conversationEngine = new ConversationEngine(
                chatLlm, retriever, userModelRepository, episodicEventRepository, config.llmMaxTokensChat);
        EvidenceExtractor evidenceExtractor = new EvidenceExtractor(heavyLlm, consolidator, config.llmMaxTokensEvidence);

        // ── Discordアダプタの起動 ─────────────────────
        if (config.discordToken.isBlank()) {
            logger.warning(".envにDISCORD_TOKENが未設定のため、Discord疎通はスキップします。");
            logger.info("紬希のセットアップが完了しました。（Discord未接続）");
            initialSetupManager.shutdown();
            withdrawalManager.shutdown();
            diaryManager.shutdown();
            forcedWithdrawalManager.shutdown();
            dispatcher.shutdown();
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            return;
        }

        DiscordAdapter adapter = new DiscordAdapter(
                conversationEngine, episodicEventRepository, evidenceExtractor, dataSubjectRightsService,
                initialSetupRepositoryForChatGate);
        JDA jda = DiscordAdapter.start(config.discordToken, adapter,
                initialSetupListener, withdrawalListener, diaryListener, broadcastListener, forcedWithdrawalListener);
        initialSetupManager.bootstrapGuilds(jda);
        initialSetupManager.runLegacyBackfill(jda, episodicEventRepository); // 既存ユーザーの救済（バックフィル）
        withdrawalManager.ensureWithdrawalRequestChannelsForAllGuilds(jda);
        diaryManager.bootstrapGuilds(jda); // ここでdiary_queueワーカーも起動する
        broadcastManager.bootstrapGuilds(jda); // お知らせ配信チャンネルの存在保証
        forcedWithdrawalManager.bootstrapGuilds(jda); // 強制退会チャンネルの存在保証・未完了スケジュールの再構築

        jda.updateCommands().addCommands(tsumugi.diary.DiaryListener.commandData()).queue();

        logger.info("紬希のDiscord接続が完了しました。");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("紬希をシャットダウンします...");
            adapter.shutdown();
            initialSetupManager.shutdown();
            withdrawalManager.shutdown();
            diaryManager.shutdown();
            forcedWithdrawalManager.shutdown();
            dispatcher.shutdown();
            jda.shutdown();
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }));
    }

    private static void runSmokeTest(MemoryConsolidator consolidator, MemoryRetriever retriever) {
        long testUserId = 0L; // スモークテスト専用の仮ユーザーID

        Evidence evidence = new Evidence();
        evidence.category = EvidenceCategory.HABIT;
        evidence.topic = "朝の散歩";
        evidence.content = "毎朝近所を散歩する習慣がある";
        evidence.confidence = 0.8;
        evidence.polarity = Polarity.POSITIVE;

        try {
            Evidence saved = consolidator.consolidate(testUserId, evidence);
            logger.info("Evidence保存テストOK: id=" + saved.id
                    + " embedding=" + (saved.embedding != null ? "取得成功(" + saved.embedding.length + "次元)" : "取得失敗"));

            List<RetrievalResult> results = retriever.retrieve(testUserId, "健康のためにやっていること", 5);
            logger.info("検索テスト結果件数: " + results.size());
            for (RetrievalResult r : results) {
                logger.info(String.format("  - score=%.3f topic=%s content=%s",
                        r.score(), r.evidence().topic, r.evidence().content));
            }
        } catch (Exception e) {
            logger.warning("スモークテストに失敗しました（LM Studioが起動していない可能性があります）: " + e.getMessage());
        }
    }
}
