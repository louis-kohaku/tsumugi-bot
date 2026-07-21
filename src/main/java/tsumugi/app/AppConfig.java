package tsumugi.app;

import io.github.cdimascio.dotenv.Dotenv;

/** .envから読み込んだ設定値をまとめて保持する。 */
public final class AppConfig {

    public final String discordToken;
    public final String lmStudioBaseUrl;
    public final String lmStudioChatModel;
    public final String lmStudioEmbeddingModel;

    /** 共有DB（initial_setup / withdrawal / membership_events等、運用管理データ用）のパス。 */
    public final String dbPath;

    /**
     * ユーザーごとの記憶層DB（episodic_events / evidence / evidence_vec / user_model）を
     * 配置するベースディレクトリ。実際のDBファイルは userDbDir/{userId}/tsumugi.db に作られる。
     */
    public final String userDbDir;

    public final String sqliteVecExtensionPath;

    private AppConfig(Dotenv env) {
        this.discordToken = env.get("DISCORD_TOKEN", "");
        this.lmStudioBaseUrl = env.get("LM_STUDIO_BASE_URL", "http://localhost:1234");
        this.lmStudioChatModel = env.get("LM_STUDIO_CHAT_MODEL", "");
        this.lmStudioEmbeddingModel = env.get("LM_STUDIO_EMBEDDING_MODEL", "");
        this.dbPath = env.get("TSUMUGI_DB_PATH", "data/tsumugi.db");
        this.userDbDir = env.get("TSUMUGI_USER_DB_DIR", "data/users");
        this.sqliteVecExtensionPath = env.get("SQLITE_VEC_EXTENSION_PATH", "");
    }

    public static AppConfig load() {
        Dotenv env = Dotenv.configure()
                .ignoreIfMissing()
                .ignoreIfMalformed()
                .load();
        return new AppConfig(env);
    }
}
