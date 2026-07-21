package tsumugi.memory.store.sqlite;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * SQLite接続を生成し、起動時にsqlite-vec拡張をロードする。
 * 拡張のロードに失敗した場合でも起動自体は続行し、
 * ベクトル検索機能のみを無効化する（キーワード検索にフォールバック）。
 */
public final class SqliteConnectionFactory {

    private static final Logger logger = Logger.getLogger(SqliteConnectionFactory.class.getName());

    private final Path dbPath;
    private final String vecExtensionPath;
    private volatile boolean vecAvailable = false;
    private volatile boolean vecChecked = false;

    public SqliteConnectionFactory(Path dbPath, String vecExtensionPath) {
        this.dbPath = dbPath;
        this.vecExtensionPath = vecExtensionPath;
    }

    public boolean isVecAvailable() {
        return vecAvailable;
    }

    public Connection open() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.enableLoadExtension(true);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, config.toProperties());

        if (!vecChecked) {
            synchronized (this) {
                if (!vecChecked) {
                    try (Statement st = conn.createStatement()) {
                        st.execute("SELECT load_extension('" + vecExtensionPath + "')");
                        vecAvailable = true;
                    } catch (SQLException e) {
                        logger.warning("sqlite-vec拡張のロードに失敗しました。ベクトル検索は無効化されます: " + e.getMessage());
                        vecAvailable = false;
                    }
                    vecChecked = true;
                }
            }
        } else if (vecAvailable) {
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT load_extension('" + vecExtensionPath + "')");
            } catch (SQLException e) {
                logger.warning("sqlite-vec拡張のロードに失敗しました（既存判定を維持）: " + e.getMessage());
            }
        }

        SqliteSchema.migrate(conn);
        if (vecAvailable) {
            SqliteSchema.migrateVecTable(conn);
        }
        return conn;
    }
}
