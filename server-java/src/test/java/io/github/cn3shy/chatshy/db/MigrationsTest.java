package io.github.cn3shy.chatshy.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.cn3shy.chatshy.TestDb;

class MigrationsTest {

    @Test
    void freshDatabaseIsIdempotent() throws Exception {
        try (TestDb db = new TestDb()) {
            Migrations migrations = new Migrations(db.dataSource(), db.jdbc());
            migrations.migrate();
            migrations.migrate();

            assertThat(tableNames(db.jdbc())).contains(
                    "threads", "messages", "jobs", "settings", "schema_version", "providers");
            assertThat(columnNames(db.jdbc(), "threads")).contains(
                    "seq", "source_ip", "source_ua", "source_client", "source_fingerprint", "blacklisted");
            assertThat(columnTypes(db.jdbc(), "threads")).containsEntry("created_at", "bigint");
            assertThat(columnTypes(db.jdbc(), "messages")).containsEntry("created_at", "bigint");
            assertThat(columnTypes(db.jdbc(), "jobs")).containsEntry("deadline_at", "bigint");
            assertThat(columnTypes(db.jdbc(), "providers")).containsEntry("updated_at", "bigint");
            assertThat(db.jdbc().queryForObject("SELECT version FROM schema_version", Integer.class))
                    .isEqualTo(Migrations.SCHEMA_VERSION);
        }
    }

    @Test
    void pendingJobsAreDroppedOnRestart() throws Exception {
        try (TestDb db = new TestDb()) {
            Migrations migrations = new Migrations(db.dataSource(), db.jdbc());
            long now = System.currentTimeMillis();
            db.jdbc().update("INSERT INTO threads (id, protocol, created_at, updated_at) VALUES ('t1','openai',?,?)",
                    now, now);
            db.jdbc().update("""
                    INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, deadline_at, created_at)
                    VALUES ('j1','t1','openai','stream','pending','{}',?,?)
                    """, now, now);

            migrations.migrate();
            assertThat(db.jdbc().queryForObject("SELECT status FROM jobs WHERE id='j1'", String.class))
                    .isEqualTo("dropped");
        }
    }

    private static List<String> tableNames(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()",
                String.class);
    }

    private static List<String> columnNames(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                String.class, table);
    }

    private static Map<String, String> columnTypes(JdbcTemplate jdbc, String table) {
        return jdbc.query(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ?",
                rs -> {
                    Map<String, String> out = new java.util.HashMap<>();
                    while (rs.next()) out.put(rs.getString(1), rs.getString(2));
                    return out;
                }, table);
    }
}
