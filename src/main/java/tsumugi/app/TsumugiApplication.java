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
import java.util.List;
import java.util.logging.Logger;

/**
 * 紬希の起動エントリーポイント。
 *
 * DB構成は2種類に分かれている:
 *  - 共有DB（config.dbPath）: initial_setup / withdrawal / membership_events /
 *    user_db_folders など、Discordサーバー運用に関わるデータ。
 *    管理者が横断的に扱う・状態検索が必要なため共有のまま。
 *  - ユーザーごとDB（config.userDbDir/{表示名+登録日時}/tsumugi.db）: episodic_events /
 *    evidence / evidence_vec / user_model など、本人の記憶そのもの。
 *    userId→フォルダ名の対応はUserDbFolderRepository（共有DB）が持ち、
 *    UserConnectionFactoryRegistryがそれを使って実際のファイルを解決する。
 *
 * 利用規約第10〜13条・AI利用者権利章典に対応するDataSubjectRightsServiceも
 * ここで組み立て、DiscordAdapterへ渡す。
 *
 * WithdrawalRepositoryはInitialSetupManager（内部のMembershipManagerが再入室時の
 * 引継ぎ判定に使う）とWithdrawalManagerの両方から参照される共有インスタンスのため、
 * ここで先に生成してから両者に渡す。UserConnectionFactoryRegistryも同様に、
 * 記憶層Repository群とInitialSetupManager（名前登録時のフォルダ割り当て）の
 * 両方から参照される共有インスタンスとして先に生成する。
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
        // 起動時に一度接続を張ってマイグレーションを走らせる
        sharedConnectionFactory.open().close();
        logger.info("共有DB接続OK: " + sharedDbPath);

        // ── ユーザーごとDB（記憶層）のセットアップ ────────────
        // フォルダ名は「表示名+登録日時」。userId→フォルダ名の対応は共有DB上のuser_db_foldersが持つ。
        Path userDbDir = Paths.get(config.userDbDir);
        Files.createDirectories(userDbDir);
        UserDbFolderRepository userDbFolderRepository = new UserDbFolderRepository(sharedConnectionFactory);
        UserConnectionFactoryRegistry userDbRegistry =
                new UserConnectionFactoryRegistry(userDbDir, config.sqliteVecExtensionPath, userDbFolderRepository);
        logger.info("ユーザーごとDBのベースディレクトリ: " + userDbDir);

        SqliteEvidenceRepository evidenceRepository = new SqliteEvidenceRepository(userDbRegistry);
        SqliteUserModelRepository userModelRepository = new SqliteUserModelRepository(userDbRegistry);
        SqliteEpisodicEventRepository episodicEventRepository = new SqliteEpisodicEventRepository(userDbRegistry);

        // 利用規約第10条(閲覧権)・第12条(削除権)・第13条(エクスポート権)対応
        DataSubjectRightsService dataSubjectRightsService = new DataSubjectRightsService(
                episodicEventRepository, evidenceRepository, userModelRepository);

        // ── 退会機能のRepositoryを先に生成（初期設定側のMembershipManagerと共有するため） ──
        WithdrawalRepository withdrawalRepository = new SqliteWithdrawalRepository(sharedConnectionFactory);

        // ── 初期設定フロー（入退室記録・引継ぎ確認・ユーザー用DBフォルダの命名を含む）のセットアップ ──
        InitialSetupManager initialSetupManager =
                InitialSetupManager.createDefault(sharedConnectionFactory, withdrawalRepository, userDbRegistry);
        InitialSetupListener initialSetupListener = new InitialSetupListener(initialSetupManager);

        // ── 退会フローのセットアップ（初期設定と同じ管理者ログチャンネルを使い回す） ──
        WithdrawalManager withdrawalManager = WithdrawalManager.createDefault(
                withdrawalRepository, dataSubjectRightsService, initialSetupManager.getChannelService());
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
        withdrawalManager.ensureWithdrawalRequestChannelsForAllGuilds(jda);
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
        long testUserId = 0L; // スモークテスト専用の仮ユーザーID（data/users/unregistered_0_.../tsumugi.db が作られる）

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
