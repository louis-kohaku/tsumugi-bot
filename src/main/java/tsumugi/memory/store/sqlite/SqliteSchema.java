package tsumugi.memory.store.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite + sqlite-vec のスキーマ定義とマイグレーション。
 * すみれのJSONL方式（episodic/evidence/user_model）をテーブル化したもの。
 */
public final class SqliteSchema {

    private SqliteSchema() {}

    public static void migrate(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");

            st.execute("""
                CREATE TABLE IF NOT EXISTS episodic_events (
                    id TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    occurred_at TEXT NOT NULL,
                    channel_type TEXT NOT NULL,
                    raw_text TEXT NOT NULL,
                    speaker TEXT NOT NULL,
                    logical_date TEXT NOT NULL,
                    linked_session_id TEXT
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_episodic_user_date
                ON episodic_events(user_id, logical_date);
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS evidence (
                    id TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    topic TEXT NOT NULL,
                    content TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    polarity TEXT NOT NULL,
                    source_event_ids TEXT NOT NULL,
                    extracted_at TEXT NOT NULL,
                    supersedes TEXT,
                    status TEXT NOT NULL
                );
            """);
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_evidence_user_category_status
                ON evidence(user_id, category, status);
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS user_model (
                    user_id INTEGER PRIMARY KEY,
                    payload TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
            """);
        }
    }

    /** sqlite-vec拡張がロードできた場合のみ呼ぶ、ベクトル仮想テーブルの作成 */
    public static void migrateVecTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS evidence_vec USING vec0(
                    evidence_id TEXT PRIMARY KEY,
                    embedding FLOAT[768]
                );
            """);
        }
    }
}