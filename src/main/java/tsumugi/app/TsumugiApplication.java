package tsumugi.app;

import net.dv8tion.jda.api.JDA;
import okhttp3.OkHttpClient;
import tsumugi.conversation.ConversationEngine;
import tsumugi.core.model.TsumugiModel.Evidence;
import tsumugi.core.model.TsumugiModel.EvidenceCategory;
import tsumugi.core.model.TsumugiModel.Polarity;
import tsumugi.discord.DiscordAdapter;
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
 * 【変更点: 再入室時の引継ぎ確認（はい/いいえ）を実データに反映できるよう配線を追加】
 * InitialSetupManager.createDefault() に、記憶層の各種Repository
 * （DataSubjectRightsService / EvidenceRepository / AnonymizedDataRepository）を
 * 渡すようにした。これにより、退会（記名保持）済みユーザーが再入室した際、
 *   ・「はい」（引き継ぐ）→ 同じuserIdの既存DBフォルダをそのまま使い続ける
 *   ・「いいえ」（引き継がない）→ Evidenceを匿名化して保存したうえで元データを削除する
 * という実処理がInitialSetupService側で行えるようになる。
 * いずれも下のブロックで元々生成済みのインスタンスをそのまま渡すだけで、
 * 新規のコンポーネントは増えていない。
 *
 * 【変更点: LM Studioへの問い合わせを1本のディスパッチャに集約】（既存）
 * 会話応答・Evidence抽出・日記の総評生成のLM Studio呼び出しは全てLaneLlmDispatcher
 * （ワーカースレッド1本）経由に集約されている。詳細はLaneLlmDispatcher参照。
 *
 * DB構成（変更なし）:
 *  - 共有DB（config.dbPath）: initial_setup / withdrawal / membership_events /
 *    user_db_folders / diary_records / diary_queue / anonymized_evidence など。
 *  - ユーザーごとDB（config.userDbDir/{表示名+登録日時}/tsumugi.db）: episodic_events /
 *    evidence / evidence_vec / user_model など。
 */
public final class TsumugiApplication {

    private static final Logger logger = Logger.getLogger(TsumugiApplication.class.getName());

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        logger.info("紬希を起動します...");

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

        // 退会時「匿名化して保存」・再入室時「引継ぎ辞退（匿名化保存）」の両方で使う匿名データの保存先。
        // 個人と紐付く情報は一切保存しない（AnonymizedDataRepository参照）。
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

        // ── LLM連携層のセットアップ ────────────────────
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .build();

        // 重量モデルが未設定の場合は、通常会話用モデルにフォールバックする
        // （設定ミスで即座に起動失敗にはせず、レーン分離自体は機能させる）。
        String heavyModel = config.lmStudioHeavyModel.isBlank() ? config.lmStudioChatModel : config.lmStudioHeavyModel;
        if (config.lmStudioHeavyModel.isBlank()) {
            logger.warning("LM_STUDIO_HEAVY_MODELが未設定のため、日記・Evidence抽出も会話用モデルを使用します。");
        }

        LmStudioGateway chatGateway = new LmStudioGateway(
                config.lmStudioBaseUrl, config.lmStudioChatModel, config.lmStudioEmbeddingModel, httpClient);
        LmStudioGateway heavyGateway = new LmStudioGateway(
                config.lmStudioBaseUrl, heavyModel, config.lmStudioEmbeddingModel, httpClient);

        // CHAT=軽量・即時最優先 / DIARY=重量・CHATと同格の即時 / HEAVY=重量・アイドル時のみ
        Map<LlmLane, LlmClient> llmClientsByLane = new EnumMap<>(LlmLane.class);
        llmClientsByLane.put(LlmLane.CHAT, chatGateway);
        llmClientsByLane.put(LlmLane.DIARY, heavyGateway);
        llmClientsByLane.put(LlmLane.HEAVY, heavyGateway);

        Map<LlmLane, EmbeddingClient> embeddingClientsByLane = new EnumMap<>(LlmLane.class);
        embeddingClientsByLane.put(LlmLane.CHAT, chatGateway);
        embeddingClientsByLane.put(LlmLane.DIARY, heavyGateway);
        embeddingClientsByLane.put(LlmLane.HEAVY, heavyGateway);

        LaneLlmDispatcher dispatcher = new LaneLlmDispatcher(llmClientsByLane, embeddingClientsByLane);

        LlmClient chatLlm = new LaneLlmClient(dispatcher, LlmLane.CHAT);
        LaneEmbeddingClient chatEmbed = new LaneEmbeddingClient(dispatcher, LlmLane.CHAT);
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

        // ── 日記機能のセットアップ（要約生成はDIARYレーン） ──────────
        tsumugi.initialsetup.store.InitialSetupRepository initialSetupRepositoryForDiary =
                new tsumugi.initialsetup.store.sqlite.SqliteInitialSetupRepository(sharedConnectionFactory);
        tsumugi.diary.DiaryManager diaryManager = tsumugi.diary.DiaryManager.createDefault(
                sharedConnectionFactory, diaryLlm, initialSetupRepositoryForDiary);
        tsumugi.diary.DiaryListener diaryListener = new tsumugi.diary.DiaryListener(diaryManager);

        initialSetupManager.getService().setOnDisplayNameConfirmed(diaryManager::onMemberDisplayNameConfirmed);

        // ── 会話エンジン（CHATレーン）・Evidence抽出層（HEAVYレーン）のセットアップ ──
        ConversationEngine conversationEngine = new ConversationEngine(
                chatLlm, retriever, episodicEventRepository, userModelRepository);
        EvidenceExtractor evidenceExtractor = new EvidenceExtractor(heavyLlm, consolidator);

        // ── Discordアダプタの起動 ─────────────────────
        if (config.discordToken.isBlank()) {
            logger.warning(".envにDISCORD_TOKENが未設定のため、Discord疎通はスキップします。");
            logger.info("紬希のセットアップが完了しました。（Discord未接続）");
            initialSetupManager.shutdown();
            withdrawalManager.shutdown();
            diaryManager.shutdown();
            dispatcher.shutdown();
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            return;
        }

        DiscordAdapter adapter = new DiscordAdapter(
                conversationEngine, episodicEventRepository, evidenceExtractor, dataSubjectRightsService);
        JDA jda = DiscordAdapter.start(config.discordToken, adapter,
                initialSetupListener, withdrawalListener, diaryListener);
        initialSetupManager.bootstrapGuilds(jda);
        withdrawalManager.ensureWithdrawalRequestChannelsForAllGuilds(jda);
        diaryManager.bootstrapGuilds(jda); // ここでdiary_queueワーカーも起動する

        jda.updateCommands().addCommands(tsumugi.diary.DiaryListener.commandData()).queue();

        logger.info("紬希のDiscord接続が完了しました。");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("紬希をシャットダウンします...");
            adapter.shutdown();
            initialSetupManager.shutdown();
            withdrawalManager.shutdown();
            diaryManager.shutdown();
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
