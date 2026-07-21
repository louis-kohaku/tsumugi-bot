package tsumugi.memory.store.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * ユーザーごとDB（{userDbDir}/{folderName}/tsumugi.db）のフォルダ名を、
 * userIdをキーに共有DB上で管理するリポジトリ。
 *
 * フォルダ名の命名規則: サニタイズした表示名 + "_" + 登録日時(yyyyMMddHHmmss)
 * 同名の表示名を選んだ別ユーザーがいても、登録日時のサフィックスで衝突を避ける
 * （それでも衝突する場合は末尾に -2, -3 ... を付与する）。
 *
 * 名前未登録（オンボーディング未完了）のユーザーが先に会話してしまうケースに備え、
 * 仮フォルダ名（unregistered_{userId}_{登録日時}）を発行できるようにしている。
 * 実際に名前が登録されたタイミングでrename()を呼び、物理フォルダの移動と
 * このテーブルの更新をUserConnectionFactoryRegistry側が行う。
 */
public final class UserDbFolderRepository {

    private static final Logger logger = Logger.getLogger(UserDbFolderRepository.class.getName());
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final SqliteConnectionFactory sharedConnectionFactory;
    private volatile boolean schemaEnsured = false;

    public UserDbFolderRepository(SqliteConnectionFactory sharedConnectionFactory) {
        this.sharedConnectionFactory = sharedConnectionFactory;
    }

    private synchronized void ensureSchema(Connection conn) throws SQLException {
        if (schemaEnsured) return;
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS user_db_folders (
                    user_id INTEGER PRIMARY KEY,
                    folder_name TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    registered_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
            """);
        }
        schemaEnsured = true;
    }

    public static final class FolderRecord {
        public long userId;
        public String folderName;
        public String displayName;
        public Instant registeredAt;
        public Instant updatedAt;
    }

    /** 既存のフォルダ割り当てを返す。未登録ならnull。 */
    public FolderRecord load(long userId) {
        String sql = "SELECT * FROM user_db_folders WHERE user_id=?";
        try (Connection conn = sharedConnectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        } catch (SQLException e) {
            logger.warning("UserDbFolderの読み込みに失敗しました (userId=" + userId + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * 初回のフォルダ割り当てを行う。既に割り当て済みならその値をそのまま返す（冪等）。
     * displayNameには、正式な表示名（初期設定完了時）でも仮の識別用文字列
     * （例: "unregistered"）でも渡せる。
     */
    public FolderRecord registerInitial(long userId, String displayName) {
        FolderRecord existing = load(userId);
        if (existing != null) return existing;

        Instant now = Instant.now();
        String folderName = buildUniqueFolderName(displayName, now, userId);
        FolderRecord record = new FolderRecord();
        record.userId = userId;
        record.folderName = folderName;
        record.displayName = displayName;
        record.registeredAt = now;
        record.updatedAt = now;
        insert(record);
        logger.info("ユーザー用DBフォルダを新規登録しました: userId=" + userId + " folderName=" + folderName);
        return record;
    }

    /**
     * 表示名の変更に伴い、フォルダ名を再計算する。
     * 登録日時（registeredAt）は変えず、そのサフィックスのまま新しい表示名部分だけ差し替える。
     * displayNameが変わっていなければ何もせず既存レコードを返す。
     *
     * @return 更新後（または変更不要だった場合はそのまま）のFolderRecord。nullは「未登録ユーザー」を意味する。
     */
    public FolderRecord rename(long userId, String newDisplayName) {
        FolderRecord existing = load(userId);
        if (existing == null) return null;
        if (existing.displayName.equals(newDisplayName)) return existing;

        String newFolderName = buildUniqueFolderName(newDisplayName, existing.registeredAt, userId);
        Instant now = Instant.now();

        String sql = """
            UPDATE user_db_folders
            SET folder_name=?, display_name=?, updated_at=?
            WHERE user_id=?
            """;
        try (Connection conn = sharedConnectionFactory.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newFolderName);
            ps.setString(2, newDisplayName);
            ps.setString(3, now.toString());
            ps.setLong(4, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("UserDbFolderのリネームに失敗しました (userId=" + userId + "): " + e.getMessage());
            return existing;
        }

        logger.info("ユーザー用DBフォルダをリネームしました: userId=" + userId
                + " " + existing.folderName + " -> " + newFolderName);

        FolderRecord updated = new FolderRecord();
        updated.userId = userId;
        updated.folderName = newFolderName;
        updated.displayName = newDisplayName;
        updated.registeredAt = existing.registeredAt;
        updated.updatedAt = now;
        return updated;
    }

    private void insert(FolderRecord record) {
        String sql = """
            INSERT INTO user_db_folders (user_id, folder_name, display_name, registered_at, updated_at)
            VALUES (?,?,?,?,?)
            """;
        try (Connection conn = sharedConnectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, record.userId);
                ps.setString(2, record.folderName);
                ps.setString(3, record.displayName);
                ps.setString(4, record.registeredAt.toString());
                ps.setString(5, record.updatedAt.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("UserDbFolderの登録に失敗しました (userId=" + record.userId + "): " + e.getMessage());
        }
    }

    private boolean folderNameTaken(String folderName) {
        String sql = "SELECT 1 FROM user_db_folders WHERE folder_name=?";
        try (Connection conn = sharedConnectionFactory.open()) {
            ensureSchema(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, folderName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            logger.warning("フォルダ名の重複確認に失敗しました (folderName=" + folderName + "): " + e.getMessage());
            return false; // 確認できない場合は重複なし扱いにして処理を止めない
        }
    }

    private String buildUniqueFolderName(String displayName, Instant timestamp, long userId) {
        String base = sanitize(displayName) + "_" + TIMESTAMP_FORMAT.format(timestamp);
        String candidate = base;
        int suffix = 2;
        while (folderNameTaken(candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
            if (suffix > 50) {
                // 万一の異常系フォールバック（実運用ではまず到達しない想定）
                candidate = base + "-" + userId;
                break;
            }
        }
        return candidate;
    }

    /** ファイルシステムで安全に使える文字列に整形する。 */
    private String sanitize(String raw) {
        String trimmed = raw == null ? "unknown" : raw.strip();
        if (trimmed.isEmpty()) trimmed = "unknown";
        // OS間で問題になりやすい文字を除去し、長さも制限する
        String cleaned = trimmed.replaceAll("[\\\\/:*?\"<>|\\s]", "");
        if (cleaned.isEmpty()) cleaned = "unknown";
        return cleaned.length() > 50 ? cleaned.substring(0, 50) : cleaned;
    }

    private FolderRecord map(ResultSet rs) throws SQLException {
        FolderRecord r = new FolderRecord();
        r.userId = rs.getLong("user_id");
        r.folderName = rs.getString("folder_name");
        r.displayName = rs.getString("display_name");
        r.registeredAt = Instant.parse(rs.getString("registered_at"));
        r.updatedAt = Instant.parse(rs.getString("updated_at"));
        return r;
    }
}
