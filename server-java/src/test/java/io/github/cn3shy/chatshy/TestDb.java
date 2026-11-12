package io.github.cn3shy.chatshy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import io.github.cn3shy.chatshy.config.ChatShyProperties;
import io.github.cn3shy.chatshy.db.Migrations;

/** 每个用例一个临时 SQLite 文件，跑完整迁移。 */
public final class TestDb implements AutoCloseable {
    private final Path dir;
    private final SQLiteDataSource dataSource;
    private final JdbcTemplate jdbc;

    public TestDb() throws IOException {
        dir = Files.createTempDirectory("chatshy-java-test-");
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + dir.resolve("test.db"));
        jdbc = new JdbcTemplate(dataSource);
        new Migrations(dataSource, jdbc).migrate();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public static ChatShyProperties properties() {
        return new ChatShyProperties(
                ":memory:", null, null, 60_000, "chatshy",
                15_000, 20_000, 1,
                new ChatShyProperties.Dounai(true, 8099, "127.0.0.1", "豆奶", "", ""));
    }

    @Override
    public void close() {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响测试结论
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }
}
