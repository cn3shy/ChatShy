package io.github.cn3shy.chatshy;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.github.cn3shy.chatshy.config.ChatShyProperties;
import io.github.cn3shy.chatshy.db.Migrations;

/** 每个用例一个独立 PostgreSQL database，跑完整迁移。 */
public final class TestDb implements AutoCloseable {
    private final PgTestServer.TestDatabase database;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public TestDb() {
        database = PgTestServer.createDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(database.url(), "postgres", "");
        dataSource = ds;
        jdbc = new JdbcTemplate(ds);
        new Migrations(dataSource, jdbc).migrate();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public String url() {
        return database.url();
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public static ChatShyProperties properties() {
        return new ChatShyProperties(
                null, null, 60_000, "chatshy",
                15_000, 20_000, 1,
                new ChatShyProperties.Dounai(true, 8099, "127.0.0.1", "豆奶", "", ""));
    }

    @Override
    public void close() {
        PgTestServer.dropDatabase(database);
    }
}
