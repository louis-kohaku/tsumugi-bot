package tsumugi.app;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.logging.Logger;

/** .envから読み込んだ設定値をまとめて保持する。 */
public final class AppConfig {

    private static final Logger logger = Logger.getLogger(AppConfig.class.getName());

    // DBファイルの衝突（同名ファイルで別プロジェクト/別環境のものを誤って開いてしまう事故）を
    // 避けるため、TSUMUGI_DB_PATH未指定時はこのディレクトリ・接頭辞でファイルを自動管理する。
    private static final String AUTO_DB_DIR = "data";
    private static final String AUTO_DB_PREFIX = "tsumugi_";
    private static final String AUTO_DB_SUFFIX = ".db";
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // ユーザーごとDB（記憶層）を配置するベースディレクトリのデフォルト値。
    // この配下に「表示名+登録日時」フォルダを1ユーザー1つ作る想定。
    private static final String DEFAULT_USER_DB_DIR = "data/users";

    public final String discordToken;
    public final String lmStudioBaseUrl;

    /** 通常会話用のモデル。応答速度を優先するため、軽量なモデルを想定。 */
    public final String lmStudioChatModel;

    /**
     * Evidence抽出（感情・性格分析）・日記の総評生成など、精度を優先したい
     * 重い処理専用のモデル。未設定の場合はlmStudioChatModelにフォールバックする
     * （AppConfig.load()時点で判定できないため、フォールバック処理はTsumugiApplication側で行う）。
     */
    public final String lmStudioHeavyModel;

    public final String lmStudioEmbeddingModel;
    public final String dbPath;
    public final String userDbDir;
    public final String sqliteVecExtensionPath;

    private AppConfig(Dotenv env) {
        this.discordToken = env.get("DISCORD_TOKEN", "");
        this.lmStudioBaseUrl = env.get("LM_STUDIO_BASE_URL", "http://localhost:1234");
        this.lmStudioChatModel = env.get("LM_STUDIO_CHAT_MODEL", "");
        this.lmStudioHeavyModel = env.get("LM_STUDIO_HEAVY_MODEL", "");
        this.lmStudioEmbeddingModel = env.get("LM_STUDIO_EMBEDDING_MODEL", "");
        this.dbPath = resolveDbPath(env);
        this.userDbDir = env.get("TSUMUGI_USER_DB_DIR", DEFAULT_USER_DB_DIR);
        this.sqliteVecExtensionPath = env.get("SQLITE_VEC_EXTENSION_PATH", "");
    }

    /**
     * DBファイルパスを決定する。
     *
     * 1. .envでTSUMUGI_DB_PATHが明示されていれば、それを最優先でそのまま使う
     *    （運用者が意図的にパスを指定しているケースなので上書きしない）。
     * 2. 未指定の場合はdataディレクトリ配下の "tsumugi_*.db" を探し、
     *    既に存在すれば一番新しいものを再利用する（再起動時に同じDBを開き続けるため）。
     * 3. どちらも無ければ、今の日時（＝このDBの登録日時）をファイル名に埋め込んだ
     *    新規ファイル名を生成する。これにより、他のプロジェクト/他環境のDBファイルと
     *    名前が衝突して開けなくなる事故を防ぐ。
     */
    private String resolveDbPath(Dotenv env) {
        String explicit = env.get("TSUMUGI_DB_PATH", "");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }

        Path dir = Paths.get(AUTO_DB_DIR);
        Optional<Path> existing = findLatestAutoDbFile(dir);
        if (existing.isPresent()) {
            String found = existing.get().toString();
            logger.info("既存のDBファイルを再利用します: " + found);
            return found;
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String generated = Paths.get(AUTO_DB_DIR, AUTO_DB_PREFIX + timestamp + AUTO_DB_SUFFIX).toString();
        logger.info("DBファイルが見つからなかったため、登録日時ベースの新規ファイル名を生成しました: " + generated);
        return generated;
    }

    /** dataディレクトリ内の "tsumugi_*.db" のうち、最終更新日時が最も新しいものを返す。 */
    private Optional<Path> findLatestAutoDbFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                dir, AUTO_DB_PREFIX + "*" + AUTO_DB_SUFFIX)) {
            Path latest = null;
            long latestModified = Long.MIN_VALUE;
            for (Path candidate : stream) {
                long modified = Files.getLastModifiedTime(candidate).toMillis();
                if (modified > latestModified) {
                    latestModified = modified;
                    latest = candidate;
                }
            }
            return Optional.ofNullable(latest);
        } catch (IOException e) {
            throw new UncheckedIOException("dataディレクトリの走査に失敗しました", e);
        }
    }

    public static AppConfig load() {
        Dotenv env = Dotenv.configure()
                .ignoreIfMissing()
                .ignoreIfMalformed()
                .load();
        return new AppConfig(env);
    }
}