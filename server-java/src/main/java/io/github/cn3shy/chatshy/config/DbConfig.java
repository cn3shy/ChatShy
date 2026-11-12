package io.github.cn3shy.chatshy.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.sql.DataSource;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Configuration
public class DbConfig {

    @Bean
    public DataSource dataSource(ChatShyProperties properties) throws IOException {
        String rawPath = properties.dbPath() == null || properties.dbPath().isBlank()
                ? "./data/chatshy.db"
                : properties.dbPath();
        String url;
        if (rawPath.equals(":memory:")) {
            url = "jdbc:sqlite::memory:";
        } else {
            Path path = Paths.get(rawPath).toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            url = "jdbc:sqlite:" + path;
        }

        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(5000);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqlite);
        sqliteDataSource.setUrl(url);

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqliteDataSource);
        // better-sqlite3 是单连接同步访问；这里同样只开一个连接，避免 SQLite 写锁竞争。
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setIdleTimeout(0);
        hikari.setMaxLifetime(0);
        hikari.setConnectionTimeout(3000);
        hikari.setPoolName("chatshy-sqlite");
        return new HikariDataSource(hikari);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
