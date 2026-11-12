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
 * PostgreSQL 基线迁移。SQLite 版本的历史迁移不再回放：
 * 旧的 SQLite 数据由 {@link SqliteImporter} 一次性导入到本 schema。
 */
@Component
public class Migrations {
    public static final int SCHEMA_VERSION = 2;

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
            CREATE TABLE IF NOT EXISTS threads (
              seq                BIGSERIAL UNIQUE,
              id                 TEXT PRIMARY KEY,
              title              TEXT NOT NULL DEFAULT '',
              protocol           TEXT NOT NULL,
              model              TEXT NOT NULL DEFAULT '',
              fingerprint        TEXT NOT NULL DEFAULT '',
              source_ip          TEXT NOT NULL DEFAULT '',
              source_ua          TEXT NOT NULL DEFAULT '',
              source_client      TEXT NOT NULL DEFAULT '',
              source_fingerprint TEXT NOT NULL DEFAULT '',
              blacklisted        INTEGER NOT NULL DEFAULT 0,
              created_at         BIGINT NOT NULL,
              updated_at         BIGINT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS messages (
              seq        BIGSERIAL UNIQUE,
              id         TEXT PRIMARY KEY,
              thread_id  TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
              role       TEXT NOT NULL,
              content    TEXT NOT NULL,
              source     TEXT NOT NULL,
              job_id     TEXT,
              created_at BIGINT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS jobs (
              id              TEXT PRIMARY KEY,
              thread_id       TEXT NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
              protocol        TEXT NOT NULL,
              mode            TEXT NOT NULL,
              status          TEXT NOT NULL,
              request_json    TEXT NOT NULL,
              reply_content   TEXT,
              deadline_at     BIGINT NOT NULL,
              created_at      BIGINT NOT NULL,
              replied_at      BIGINT,
              via             TEXT,
              provider_id     TEXT,
              forward_error   TEXT,
              forward_error_at BIGINT
            );

            CREATE TABLE IF NOT EXISTS settings (
              key   TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS providers (
              seq        BIGSERIAL UNIQUE,
              id         TEXT PRIMARY KEY,
              name       TEXT NOT NULL UNIQUE,
              base_url   TEXT NOT NULL,
              api_key    TEXT NOT NULL,
              model      TEXT NOT NULL,
              created_at BIGINT NOT NULL,
              updated_at BIGINT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_threads_source      ON threads(source_fingerprint, updated_at);
            CREATE INDEX IF NOT EXISTS idx_threads_blacklisted ON threads(blacklisted);
            CREATE INDEX IF NOT EXISTS idx_messages_thread     ON messages(thread_id);
            CREATE INDEX IF NOT EXISTS idx_jobs_thread         ON jobs(thread_id);
            CREATE INDEX IF NOT EXISTS idx_jobs_status         ON jobs(status);
            """,

            """
            CREATE INDEX IF NOT EXISTS idx_jobs_created         ON jobs(created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_jobs_protocol_status ON jobs(protocol, status);
            """,
    };
}
