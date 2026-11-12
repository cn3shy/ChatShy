package io.github.cn3shy.chatshy.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

class MigrationsTest {
    private record Raw(SQLiteDataSource dataSource, JdbcTemplate jdbc, Path dir) implements AutoCloseable {
        @Override
        public void close() {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // ignore
                    }
                });
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static Raw raw() throws Exception {
        Path dir = Files.createTempDirectory("chatshy-migrate-test-");
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        return new Raw(ds, new JdbcTemplate(ds), dir);
    }

    private static void exec(SQLiteDataSource ds, List<String> statements) throws Exception {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            for (String sql : statements) st.execute(sql);
        }
    }

    private static void setVersion(JdbcTemplate jdbc, int version) {
        jdbc.update("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        jdbc.update("DELETE FROM schema_version");
        jdbc.update("INSERT INTO schema_version (version) VALUES (?)", version);
    }

    @Test
    void freshDatabaseIsIdempotent() throws Exception {
        try (Raw raw = raw()) {
            Migrations migrations = new Migrations(raw.dataSource(), raw.jdbc());
            migrations.migrate();
            migrations.migrate();

            assertThat(tableNames(raw.jdbc())).contains(
                    "threads", "messages", "jobs", "settings", "schema_version", "providers");
            assertThat(tableNames(raw.jdbc())).doesNotContain("keys");
            assertThat(raw.jdbc().queryForObject("SELECT version FROM schema_version", Integer.class))
                    .isEqualTo(Migrations.SCHEMA_VERSION);
            assertThat(columnNames(raw.jdbc(), "threads")).contains(
                    "source_ip", "source_ua", "source_client", "source_fingerprint");
            assertThat(columnNames(raw.jdbc(), "threads")).doesNotContain("key_id");
        }
    }

    @Test
    void pendingJobsAreDroppedOnRestart() throws Exception {
        try (Raw raw = raw()) {
            Migrations migrations = new Migrations(raw.dataSource(), raw.jdbc());
            migrations.migrate();
            long now = System.currentTimeMillis();
            raw.jdbc().update("INSERT INTO threads (id, protocol, created_at, updated_at) VALUES ('t1','openai',?,?)",
                    now, now);
            raw.jdbc().update("""
                    INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
                    VALUES ('j1','t1','openai','stream','pending','{}',?,?)
                    """, now, now);

            migrations.migrate();
            assertThat(raw.jdbc().queryForObject("SELECT status FROM jobs WHERE id='j1'", String.class))
                    .isEqualTo("dropped");
        }
    }

    @Test
    void upgradesV1AndKeepsAllData() throws Exception {
        try (Raw raw = raw()) {
            exec(raw.dataSource(), Migrations.statements(Migrations.MIGRATIONS[0]));
            setVersion(raw.jdbc(), 1);

            long now = System.currentTimeMillis();
            raw.jdbc().update("INSERT INTO keys (id,name,key_hash,key_prefix,created_at) VALUES "
                    + "('k1','alice','h','sk-ab…',?)", now);
            raw.jdbc().update("INSERT INTO threads (id,key_id,title,protocol,created_at,updated_at) "
                    + "VALUES ('t1','k1','正常会话','openai',?,?)", now, now);
            raw.jdbc().update("INSERT INTO threads (id,key_id,title,protocol,created_at,updated_at) "
                    + "VALUES ('t2',NULL,'控制台测试会话','console',?,?)", now, now);
            raw.jdbc().update("INSERT INTO messages (id,thread_id,role,content,source,created_at) "
                    + "VALUES ('m1','t1','user','你好','api',?)", now);
            raw.jdbc().update("INSERT INTO messages (id,thread_id,role,content,source,created_at) "
                    + "VALUES ('m2','t2','user','控制台消息','console',?)", now);
            raw.jdbc().update("""
                    INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
                    VALUES ('j1','t1','openai','stream','replied','{}',?,?)
                    """, now, now);

            new Migrations(raw.dataSource(), raw.jdbc()).migrate();

            assertThat(tableNames(raw.jdbc())).doesNotContain("keys");
            assertThat(raw.jdbc().queryForObject("SELECT COUNT(*) FROM threads", Integer.class)).isEqualTo(2);
            assertThat(raw.jdbc().queryForObject("SELECT COUNT(*) FROM messages", Integer.class)).isEqualTo(2);
            assertThat(raw.jdbc().queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isEqualTo(1);

            List<java.util.Map<String, Object>> rows = raw.jdbc().queryForList(
                    "SELECT id, source_client, source_fingerprint FROM threads ORDER BY id");
            assertThat(rows).extracting(row -> row.get("source_client"))
                    .containsExactly("旧 Key：alice", "历史会话");
            assertThat(rows).extracting(row -> row.get("source_fingerprint"))
                    .containsExactly("legacy:t1", "legacy:t2");
        }
    }

    @Test
    void upgradesV2WithProvidersAndForwardColumns() throws Exception {
        try (Raw raw = raw()) {
            exec(raw.dataSource(), Migrations.statements(Migrations.MIGRATIONS[0]));
            exec(raw.dataSource(), Migrations.statements(Migrations.MIGRATIONS[1]));
            setVersion(raw.jdbc(), 2);

            long now = System.currentTimeMillis();
            raw.jdbc().update("INSERT INTO threads (id,protocol,title,created_at,updated_at) "
                    + "VALUES ('t1','openai','老会话',?,?)", now, now);
            raw.jdbc().update("INSERT INTO messages (id,thread_id,role,content,source,created_at) "
                    + "VALUES ('m1','t1','user','你好','api',?)", now);
            raw.jdbc().update("""
                    INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
                    VALUES ('j1','t1','openai','stream','replied','{}',?,?)
                    """, now, now);

            new Migrations(raw.dataSource(), raw.jdbc()).migrate();

            assertThat(tableNames(raw.jdbc())).contains("providers");
            assertThat(columnNames(raw.jdbc(), "jobs")).contains("via", "provider_id", "forward_error", "forward_error_at");
            assertThat(raw.jdbc().queryForObject("SELECT COUNT(*) FROM threads", Integer.class)).isEqualTo(1);
            assertThat(raw.jdbc().queryForObject("SELECT COUNT(*) FROM messages", Integer.class)).isEqualTo(1);
        }
    }

    private static List<String> tableNames(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table'", String.class);
    }

    private static List<String> columnNames(JdbcTemplate jdbc, String table) {
        return jdbc.query("PRAGMA table_info(" + table + ")", (rs, i) -> rs.getString("name"));
    }
}
