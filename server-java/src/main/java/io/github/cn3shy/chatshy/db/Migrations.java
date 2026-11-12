package io.github.cn3shy.chatshy.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 与 Node 版 src/db/migrate.ts 逐条一致的迁移。只允许追加，不允许改历史项。
 * 现有库 schema_version=3 时是空操作，只做重启语义（pending → dropped）。
 */
@Component
public class Migrations {
    public static final int SCHEMA_VERSION = 4;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public Migrations(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    public void migrate() {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            }

            int current = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version FROM schema_version LIMIT 1")) {
                if (rs.next()) current = rs.getInt(1);
            }

            if (current < MIGRATIONS.length) {
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    for (int i = current; i < MIGRATIONS.length; i++) {
                        for (String sql : statements(MIGRATIONS[i])) {
                            st.execute(sql);
                        }
                    }
                    st.execute("DELETE FROM schema_version");
                    st.execute("INSERT INTO schema_version (version) VALUES (" + MIGRATIONS.length + ")");
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("数据库迁移失败", e);
        }

        // 重启语义：内存中的挂起请求随进程消失，遗留 pending 一律标记 dropped
        jdbc.update("UPDATE jobs SET status = 'dropped' WHERE status = 'pending'");
    }

    static List<String> statements(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.stripLeading().startsWith("--")) continue;
            cleaned.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    static final String[] MIGRATIONS = {
            """
            CREATE TABLE IF NOT EXISTS keys (
              id           TEXT PRIMARY KEY,
              name         TEXT NOT NULL,
              key_hash     TEXT NOT NULL UNIQUE,
              key_prefix   TEXT NOT NULL,
              enabled      INTEGER NOT NULL DEFAULT 1,
              timeout_ms   INTEGER,
              daily_limit  INTEGER,
              max_pending  INTEGER NOT NULL DEFAULT 5,
              created_at   INTEGER NOT NULL,
              last_used_at INTEGER
            );

            CREATE TABLE IF NOT EXISTS threads (
              id          TEXT PRIMARY KEY,
              key_id      TEXT REFERENCES keys(id) ON DELETE CASCADE,
              title       TEXT NOT NULL DEFAULT '',
              protocol    TEXT NOT NULL,
              model       TEXT NOT NULL DEFAULT '',
              fingerprint TEXT NOT NULL DEFAULT '',
              created_at  INTEGER NOT NULL,
              updated_at  INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS messages (
              id         TEXT PRIMARY KEY,
              thread_id  TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
              role       TEXT NOT NULL,
              content    TEXT NOT NULL,
              source     TEXT NOT NULL,
              job_id     TEXT,
              created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS jobs (
              id            TEXT PRIMARY KEY,
              thread_id     TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
              protocol      TEXT NOT NULL,
              mode          TEXT NOT NULL,
              status        TEXT NOT NULL,
              request_json  TEXT NOT NULL,
              reply_content TEXT,
              deadline_at   INTEGER NOT NULL,
              created_at    INTEGER NOT NULL,
              replied_at    INTEGER
            );

            CREATE TABLE IF NOT EXISTS settings (
              key   TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_keys_hash        ON keys(key_hash);
            CREATE INDEX IF NOT EXISTS idx_threads_key      ON threads(key_id);
            CREATE INDEX IF NOT EXISTS idx_messages_thread  ON messages(thread_id);
            CREATE INDEX IF NOT EXISTS idx_jobs_thread      ON jobs(thread_id);
            CREATE INDEX IF NOT EXISTS idx_jobs_status      ON jobs(status);
            """,

            """
            ALTER TABLE threads ADD COLUMN source_ip          TEXT NOT NULL DEFAULT '';
            ALTER TABLE threads ADD COLUMN source_ua          TEXT NOT NULL DEFAULT '';
            ALTER TABLE threads ADD COLUMN source_client      TEXT NOT NULL DEFAULT '';
            ALTER TABLE threads ADD COLUMN source_fingerprint TEXT NOT NULL DEFAULT '';

            UPDATE threads SET source_client =
              CASE WHEN key_id IS NULL THEN '历史会话'
                   ELSE '旧 Key：' || (SELECT name FROM keys WHERE keys.id = threads.key_id) END;

            UPDATE threads SET source_fingerprint = 'legacy:' || id;

            DROP INDEX IF EXISTS idx_threads_key;
            ALTER TABLE threads DROP COLUMN key_id;

            DROP TABLE keys;

            CREATE INDEX IF NOT EXISTS idx_threads_source ON threads(source_fingerprint, updated_at);
            """,

            """
            CREATE TABLE IF NOT EXISTS providers (
              id         TEXT PRIMARY KEY,
              name       TEXT NOT NULL UNIQUE,
              base_url   TEXT NOT NULL,
              api_key    TEXT NOT NULL,
              model      TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            );

            ALTER TABLE jobs ADD COLUMN via              TEXT;
            ALTER TABLE jobs ADD COLUMN provider_id      TEXT;
            ALTER TABLE jobs ADD COLUMN forward_error    TEXT;
            ALTER TABLE jobs ADD COLUMN forward_error_at INTEGER;
            """,

            """
            ALTER TABLE threads ADD COLUMN blacklisted INTEGER NOT NULL DEFAULT 0;
            CREATE INDEX IF NOT EXISTS idx_threads_blacklisted ON threads(blacklisted);
            """,
    };
}
