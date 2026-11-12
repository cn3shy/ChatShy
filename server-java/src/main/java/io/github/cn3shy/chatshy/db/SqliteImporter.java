package io.github.cn3shy.chatshy.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 一次性 SQLite → PostgreSQL 数据导入工具。
 *
 * <p>运行示例：
 * <pre>
 * mvn -Psqlite-import -q exec:java \
 *   -Dexec.mainClass=io.github.cn3shy.chatshy.db.SqliteImporter \
 *   -Dexec.args="--sqlite ./data/chatshy.db --pg-url jdbc:postgresql://127.0.0.1:5432/chatshy --pg-user chatshy --pg-password secret"
 * </pre>
 *
 * <p>目标库非空时默认拒绝；确认覆盖后加 {@code --force}。
 * 该工具兼容旧的 SQLite schema（缺少 source_* / providers / jobs 新列时补默认值）。
 */
public final class SqliteImporter {

    private SqliteImporter() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        run(required(opts, "sqlite"), required(opts, "pg-url"),
                opts.getOrDefault("pg-user", "chatshy"), opts.getOrDefault("pg-password", ""),
                opts.containsKey("force"));
    }

    /** 供命令行与测试共用。 */
    public static void run(String sqlitePath, String pgUrl, String pgUser, String pgPassword,
                           boolean force) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Class.forName("org.postgresql.Driver");

        DriverManagerDataSource pgDs = new DriverManagerDataSource(pgUrl, pgUser, pgPassword);
        JdbcTemplate pg = new JdbcTemplate(pgDs);
        new Migrations(pgDs, pg).migrate();

        long existing = count(pg, "threads") + count(pg, "messages") + count(pg, "jobs")
                + count(pg, "providers") + count(pg, "settings");
        if (existing > 0 && !force) {
            throw new IllegalStateException("目标 PostgreSQL 非空（" + existing + " 行）；确认覆盖请加 --force");
        }
        if (force) {
            pg.execute("TRUNCATE TABLE messages, jobs, providers, settings, threads RESTART IDENTITY CASCADE");
        }

        try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
            Set<String> threadCols = columns(sqlite, "threads");
            Set<String> messageCols = columns(sqlite, "messages");
            Set<String> jobCols = columns(sqlite, "jobs");
            boolean hasSettings = tableExists(sqlite, "settings");
            boolean hasProviders = tableExists(sqlite, "providers");

            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(pgDs));
            tx.executeWithoutResult(status -> {
                try {
                    if (hasSettings) copySettings(sqlite, pg);
                    if (!threadCols.isEmpty()) copyThreads(sqlite, pg, threadCols);
                    if (!messageCols.isEmpty()) copyMessages(sqlite, pg, messageCols);
                    if (!jobCols.isEmpty()) copyJobs(sqlite, pg, jobCols);
                    if (hasProviders) copyProviders(sqlite, pg);
                } catch (SQLException e) {
                    throw new IllegalStateException("导入失败", e);
                }
            });
        }

        System.out.printf("迁移完成：threads=%d messages=%d jobs=%d settings=%d providers=%d%n",
                count(pg, "threads"), count(pg, "messages"), count(pg, "jobs"),
                count(pg, "settings"), count(pg, "providers"));
    }

    private static void copySettings(Connection sqlite, JdbcTemplate pg) throws SQLException {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT key, value FROM settings")) {
            while (rs.next()) {
                pg.update("INSERT INTO settings (key, value) VALUES (?, ?) "
                                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value",
                        rs.getString(1), rs.getString(2));
            }
        }
    }

    private static void copyThreads(Connection sqlite, JdbcTemplate pg, Set<String> cols) throws SQLException {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM threads")) {
            while (rs.next()) {
                String id = rs.getString("id");
                String createdAt = text(rs, cols, "created_at", "0");
                pg.update("""
                        INSERT INTO threads (id, title, protocol, model, fingerprint,
                                             source_ip, source_ua, source_client, source_fingerprint,
                                             blacklisted, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        id,
                        text(rs, cols, "title", ""),
                        text(rs, cols, "protocol", "openai"),
                        text(rs, cols, "model", ""),
                        text(rs, cols, "fingerprint", ""),
                        text(rs, cols, "source_ip", ""),
                        text(rs, cols, "source_ua", ""),
                        text(rs, cols, "source_client", "历史会话"),
                        text(rs, cols, "source_fingerprint", "legacy:" + id),
                        integer(rs, cols, "blacklisted", 0),
                        number(rs, cols, "created_at", 0L),
                        number(rs, cols, "updated_at", number(rs, cols, "created_at", 0L)));
            }
        }
    }

    private static void copyMessages(Connection sqlite, JdbcTemplate pg, Set<String> cols) throws SQLException {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM messages")) {
            while (rs.next()) {
                pg.update("""
                        INSERT INTO messages (id, thread_id, role, content, source, job_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        rs.getString("id"),
                        rs.getString("thread_id"),
                        text(rs, cols, "role", "user"),
                        text(rs, cols, "content", ""),
                        text(rs, cols, "source", "api"),
                        nullableText(rs, cols, "job_id"),
                        number(rs, cols, "created_at", 0L));
            }
        }
    }

    private static void copyJobs(Connection sqlite, JdbcTemplate pg, Set<String> cols) throws SQLException {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM jobs")) {
            while (rs.next()) {
                pg.update("""
                        INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json,
                                          reply_content, deadline_at, created_at, replied_at,
                                          via, provider_id, forward_error, forward_error_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        rs.getString("id"),
                        rs.getString("thread_id"),
                        text(rs, cols, "protocol", "openai"),
                        text(rs, cols, "mode", "stream"),
                        text(rs, cols, "status", "dropped"),
                        text(rs, cols, "request_json", "{}"),
                        nullableText(rs, cols, "reply_content"),
                        number(rs, cols, "deadline_at", 0L),
                        number(rs, cols, "created_at", 0L),
                        nullableNumber(rs, cols, "replied_at"),
                        nullableText(rs, cols, "via"),
                        nullableText(rs, cols, "provider_id"),
                        nullableText(rs, cols, "forward_error"),
                        nullableNumber(rs, cols, "forward_error_at"));
            }
        }
    }

    private static void copyProviders(Connection sqlite, JdbcTemplate pg) throws SQLException {
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM providers")) {
            while (rs.next()) {
                pg.update("""
                        INSERT INTO providers (id, name, base_url, api_key, model, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        rs.getString("id"), rs.getString("name"), rs.getString("base_url"),
                        rs.getString("api_key"), rs.getString("model"),
                        rs.getLong("created_at"), rs.getLong("updated_at"));
            }
        }
    }

    private static long count(JdbcTemplate pg, String table) {
        Long value = pg.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }

    private static boolean tableExists(Connection sqlite, String table) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Set<String> columns(Connection sqlite, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        if (!tableExists(sqlite, table)) return out;
        try (Statement st = sqlite.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) out.add(rs.getString("name"));
        }
        return out;
    }

    private static String text(ResultSet rs, Set<String> cols, String column, String fallback) throws SQLException {
        if (!cols.contains(column)) return fallback;
        String value = rs.getString(column);
        return value == null ? fallback : value;
    }

    private static String nullableText(ResultSet rs, Set<String> cols, String column) throws SQLException {
        return cols.contains(column) ? rs.getString(column) : null;
    }

    private static int integer(ResultSet rs, Set<String> cols, String column, int fallback) throws SQLException {
        if (!cols.contains(column)) return fallback;
        int value = rs.getInt(column);
        return rs.wasNull() ? fallback : value;
    }

    private static long number(ResultSet rs, Set<String> cols, String column, long fallback) throws SQLException {
        if (!cols.contains(column)) return fallback;
        long value = rs.getLong(column);
        return rs.wasNull() ? fallback : value;
    }

    private static Long nullableNumber(ResultSet rs, Set<String> cols, String column) throws SQLException {
        if (!cols.contains(column)) return null;
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) continue;
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq >= 0) {
                out.put(body.substring(0, eq), body.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(body, args[++i]);
            } else {
                out.put(body, "");
            }
        }
        return out;
    }

    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数 --" + key);
        }
        return value;
    }
}
