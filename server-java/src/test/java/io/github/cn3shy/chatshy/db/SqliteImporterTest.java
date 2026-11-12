package io.github.cn3shy.chatshy.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import io.github.cn3shy.chatshy.TestDb;

class SqliteImporterTest {

    @Test
    void importsLegacySqliteDataIntoPostgres() throws Exception {
        long now = 1_700_000_000_000L;
        Path sqlite = createLegacySqlite(now);

        try (TestDb db = new TestDb()) {
            SqliteImporter.run(sqlite.toString(), db.url(), "postgres", "", false);

            assertThat(count(db, "threads")).isEqualTo(1);
            assertThat(count(db, "messages")).isEqualTo(1);
            assertThat(count(db, "jobs")).isEqualTo(1);
            assertThat(count(db, "settings")).isEqualTo(2);
            assertThat(count(db, "providers")).isZero();

            var thread = db.jdbc().queryForMap(
                    "SELECT source_client, source_fingerprint, blacklisted, created_at FROM threads WHERE id = 't1'");
            assertThat(thread.get("source_client")).isEqualTo("测试客户端");
            assertThat(thread.get("source_fingerprint")).isEqualTo("fp-1");
            assertThat(((Number) thread.get("blacklisted")).intValue()).isZero();
            assertThat(((Number) thread.get("created_at")).longValue()).isEqualTo(now);

            var job = db.jdbc().queryForMap("SELECT via, provider_id, reply_content FROM jobs WHERE id = 'j1'");
            assertThat(job.get("via")).isNull();
            assertThat(job.get("provider_id")).isNull();
            assertThat(job.get("reply_content")).isEqualTo("已回复");

            // 非空目标库默认拒绝，--force 才覆盖
            assertThatThrownBy(() -> SqliteImporter.run(sqlite.toString(), db.url(), "postgres", "", false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非空");
            SqliteImporter.run(sqlite.toString(), db.url(), "postgres", "", true);
            assertThat(count(db, "threads")).isEqualTo(1);
        }
    }

    private static int count(TestDb db, String table) {
        Integer value = db.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private static Path createLegacySqlite(long now) throws Exception {
        Path dir = Files.createTempDirectory("chatshy-sqlite-import-");
        Path file = dir.resolve("legacy.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE threads (
                      id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', protocol TEXT NOT NULL,
                      model TEXT NOT NULL DEFAULT '', fingerprint TEXT NOT NULL DEFAULT '',
                      created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                      source_ip TEXT NOT NULL DEFAULT '', source_ua TEXT NOT NULL DEFAULT '',
                      source_client TEXT NOT NULL DEFAULT '', source_fingerprint TEXT NOT NULL DEFAULT ''
                    )
                    """);
            st.execute("""
                    CREATE TABLE messages (
                      id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, role TEXT NOT NULL,
                      content TEXT NOT NULL, source TEXT NOT NULL, job_id TEXT, created_at INTEGER NOT NULL
                    )
                    """);
            st.execute("""
                    CREATE TABLE jobs (
                      id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, protocol TEXT NOT NULL, mode TEXT NOT NULL,
                      status TEXT NOT NULL, request_json TEXT NOT NULL, reply_content TEXT,
                      deadline_at INTEGER NOT NULL, created_at INTEGER NOT NULL, replied_at INTEGER
                    )
                    """);
            st.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("INSERT INTO threads (id,title,protocol,model,fingerprint,created_at,updated_at,"
                    + "source_ip,source_ua,source_client,source_fingerprint) VALUES "
                    + "('t1','老会话','openai','m','fp'," + now + "," + now + ","
                    + "'127.0.0.1','UA/1.0','测试客户端','fp-1')");
            st.execute("INSERT INTO messages (id,thread_id,role,content,source,job_id,created_at) "
                    + "VALUES ('m1','t1','user','你好','api',NULL," + now + ")");
            st.execute("INSERT INTO jobs (id,thread_id,protocol,mode,status,request_json,reply_content,"
                    + "deadline_at,created_at,replied_at) VALUES "
                    + "('j1','t1','openai','stream','replied','{}','已回复'," + now + "," + now + "," + now + ")");
            st.execute("INSERT INTO settings (key,value) VALUES ('caller_key','sk-legacy')");
            st.execute("INSERT INTO settings (key,value) VALUES ('model_name','legacy-model')");
        }
        return file;
    }
}
