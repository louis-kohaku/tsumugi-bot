package tsumugi.memory.store.sqlite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * ユーザーごとの記憶層DB（episodic_events / evidence / evidence_vec / user_model）を
 * 個別のSQLiteファイルとして管理するためのレジストリ。
 *
 * ファイル配置: {baseDir}/{folderName}/tsumugi.db
 * folderNameは「サニタイズした表示名 + 登録日時」で、実体の解決・採番は
 * UserDbFolderRepository（共有DB上のuserId→folderNameマッピング）が担う。
 *
 * 名前未登録のユーザーが先に会話した場合は "unregistered" を仮の表示名として
 * フォルダを割り当て、後から本登録（またはリネーム）された時点でrenameUser()を
 * 呼んでもらうことで、物理フォルダの移動とマッピングの更新を行う。
 *
 * SqliteConnectionFactory自体は「呼ばれるたびにopen()する」軽量な設計のため、
 * ここでのキャッシュはあくまで「どのファイルを開くか」の解決結果のキャッシュであり、
 * 永続コネクションは保持しない。
 */
public final class UserConnectionFactoryRegistry {

    private static final Logger logger = Logger.getLogger(UserConnectionFactoryRegistry.class.getName());
    private static final String DB_FILE_NAME = "tsumugi.db";
    private static final String UNREGISTERED_PLACEHOLDER_NAME = "unregistered";

    private final Path baseDir;
    private final String vecExtensionPath;
    private final UserDbFolderRepository folderRepository;
    private final Map<Long, SqliteConnectionFactory> factoriesByUserId = new ConcurrentHashMap<>();

    public UserConnectionFactoryRegistry(Path baseDir, String vecExtensionPath, UserDbFolderRepository folderRepository) {
        this.baseDir = baseDir;
        this.vecExtensionPath = vecExtensionPath;
        this.folderRepository = folderRepository;
    }

    /**
     * 指定ユーザー用のSqliteConnectionFactoryを返す。
     * まだフォルダが割り当てられていないユーザー（名前登録前に会話した等）には、
     * "unregistered_{登録日時}" の仮フォルダを割り当てる。
     */
    public SqliteConnectionFactory forUser(long userId) {
        return factoriesByUserId.computeIfAbsent(userId, this::resolveFactory);
    }

    private SqliteConnectionFactory resolveFactory(long userId) {
        UserDbFolderRepository.FolderRecord record = folderRepository.load(userId);
        if (record == null) {
            record = folderRepository.registerInitial(userId, UNREGISTERED_PLACEHOLDER_NAME);
        }
        return buildFactory(record.folderName);
    }

    private SqliteConnectionFactory buildFactory(String folderName) {
        Path userDir = baseDir.resolve(folderName);
        try {
            Files.createDirectories(userDir);
        } catch (IOException e) {
            throw new UncheckedIOException("ユーザー用DBディレクトリの作成に失敗しました (folderName=" + folderName + ")", e);
        }
        Path dbPath = userDir.resolve(DB_FILE_NAME);
        return new SqliteConnectionFactory(dbPath, vecExtensionPath);
    }

    /**
     * 表示名の登録・変更に伴い、ユーザー用DBフォルダを（必要なら）リネームする。
     * 初期設定完了時・引継ぎ登録時・将来的な改名機能から呼ばれる想定。
     *
     * 物理ディレクトリのrenameは、対象ユーザーのDBに同時アクセスが無いタイミング
     * （名前入力直後で、まだ会話が始まっていない等）で呼ばれることを前提とする。
     * 万一移動中に書き込みが走っていた場合の整合性は保証しない。
     */
    public synchronized void renameUser(long userId, String newDisplayName) {
        UserDbFolderRepository.FolderRecord before = folderRepository.load(userId);

        UserDbFolderRepository.FolderRecord after = (before == null)
                ? folderRepository.registerInitial(userId, newDisplayName)
                : folderRepository.rename(userId, newDisplayName);

        if (before != null && !before.folderName.equals(after.folderName)) {
            moveDirectory(before.folderName, after.folderName);
        }

        // 古いキャッシュを破棄し、次回forUser()呼び出し時に新しいパスで再解決させる
        factoriesByUserId.remove(userId);
    }

    private void moveDirectory(String oldFolderName, String newFolderName) {
        Path oldDir = baseDir.resolve(oldFolderName);
        Path newDir = baseDir.resolve(newFolderName);
        try {
            if (Files.exists(oldDir)) {
                Files.move(oldDir, newDir, StandardCopyOption.REPLACE_EXISTING);
                logger.info("ユーザー用DBフォルダを移動しました: " + oldDir + " -> " + newDir);
            } else {
                Files.createDirectories(newDir);
            }
        } catch (IOException e) {
            logger.warning("ユーザー用DBフォルダの移動に失敗しました (" + oldDir + " -> " + newDir + "): " + e.getMessage());
        }
    }

    /** 現在キャッシュされているユーザー数（デバッグ・監視用）。 */
    public int cachedUserCount() {
        return factoriesByUserId.size();
    }
}
