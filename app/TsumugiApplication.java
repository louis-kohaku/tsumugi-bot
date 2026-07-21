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
import tsumugi.llm.LmStudioGateway;
import tsumugi.memory.consolidate.MemoryConsolidator;
import tsumugi.memory.extract.EvidenceExtractor;
import tsumugi.memory.retrieval.MemoryRetriever;
import tsumugi.memory.retrieval.RetrievalResult;
import tsumugi.memory.rights.DataSubjectRightsService;
import tsumugi.memory.store.sqlite.SqliteConnectionFactory;
import tsumugi.memory.store.sqlite.SqliteEpisodicEventRepository;
import tsumugi.memory.store.sqlite.SqliteEvidenceRepository;
import tsumugi.memory.store.sqlite.SqliteUserModelRepository;
import tsumugi.withdrawal.WithdrawalListener;
import tsumugi.withdrawal.WithdrawalManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * 紬希の起動エントリーポイント。
 * 記憶層(SQLite+sqlite-vec) / LLM連携層(LM Studio) / 会話エンジン / Discordアダプタを
 * ここで組み立てて起動する。
 *
 * 利用規約第10〜13条・AI利用者権利章典に対応するDataSubjectRightsServiceも
 * ここで組み立て、DiscordAdapter・WithdrawalManagerへ渡す。
 */
public final class TsumugiApplication {

    private static final Logger logger = Logger.getLogger(TsumugiApplication.class.getName());

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        logger.info("紬希を起動します...");

        // ── 記憶層のセットアップ ──────────────────────
        Path dbPath = Paths.get(config.dbPath);
        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
        }
        SqliteConnectionFactory connectionFactory =
                new SqliteConnectionFactory(dbPath, config.sqliteVecExtensionPath);

        SqliteEvidenceRepository evidenceRepository = new SqliteEvidenceRepository(connectionFactory);
        SqliteUserModelRepository userModelRepository = new SqliteUserModelRepository(connectionFactory);
        SqliteEpisodicEventRepository episodicEventRepository = new SqliteEpisodicEventRepository(connectionFactory);

        // 起動時に一度接続を張ってマイグレーション（＆sqlite-vec拡張ロード可否判定）を走らせる
        connectionFactory.open().close();
        logger.info("SQLite接続OK。sqlite-vec利用可否: " + connectionFactory.isVecAvailable());

        // 利用規約第10条(閲覧権)・第12条(削除権)・第13条(エクスポート権)対応
        DataSubjectRightsService dataSubjectRightsService = new DataSubjectRightsService(
                episodicEventRepository, evidenceRepository, userModelRepository);

        // ── 初期設定フローのセットアップ ────────────────
        InitialSetupManager initialSetupManager = InitialSetupManager.createDefault(connectionFactory);
        InitialSetupListener initialSetupListener = new InitialSetupListener(initialSetupManager);

        // ── 退会フローのセットアップ ─────────────────────
        // 記憶層の削除権サービス・Evidenceリポジトリを共用する（匿名保存・通常削除の実処理に使うため）。
        WithdrawalManager withdrawalManager = WithdrawalManager.createDefault(
                connectionFactory, evidenceRepository, dataSubjectRightsService);
        WithdrawalListener withdrawalListener = new WithdrawalListener(withdrawalManager);

        // ── LLM連携層のセットアップ ────────────────────
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .build();

        LmStudioGateway llmGateway = new LmStudioGateway(
                config.lmStudioBaseUrl,
                config.lmStudioChatModel,
                config.lmStudioEmbeddingModel,
                httpClient);

        MemoryConsolidator consolidator = new MemoryConsolidator(userModelRepository, evidenceRepository, llmGateway);
        MemoryRetriever retriever = new MemoryRetriever(evidenceRepository, llmGateway);

        if (config.lmStudioChatModel.isBlank()) {
            logger.warning(".envにLM_STUDIO_CHAT_MODELが未設定のため、LLM疎通テストをスキップします。");
        } else {
            runSmokeTest(consolidator, retriever);
        }

        // ── 会話エンジン・Evidence抽出層のセットアップ ──────
        ConversationEngine conversationEngine = new ConversationEngine(
                llmGateway, retriever, episodicEventRepository, userModelRepository);
        EvidenceExtractor evidenceExtractor = new EvidenceExtractor(llmGateway, consolidator);

        // ── Discordアダプタの起動 ─────────────────────
        if (config.discordToken.isBlank()) {
            logger.warning(".envにDISCORD_TOKENが未設定のため、Discord疎通はスキップします。");
            logger.info("紬希のセットアップが完了しました。（Discord未接続）");
            initialSetupManager.shutdown();
            withdrawalManager.shutdown();
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
            return;
        }

        DiscordAdapter adapter = new DiscordAdapter(
                conversationEngine, episodicEventRepository, evidenceExtractor, dataSubjectRightsService);
        JDA jda = DiscordAdapter.start(config.discordToken, adapter, initialSetupListener, withdrawalListener);
        initialSetupManager.bootstrapGuilds(jda);
        withdrawalManager.bootstrapGuilds(jda);
        logger.info("紬希のDiscord接続が完了しました。");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("紬希をシャットダウンします...");
            adapter.shutdown();
            initialSetupManager.shutdown();
            withdrawalManager.shutdown();
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
