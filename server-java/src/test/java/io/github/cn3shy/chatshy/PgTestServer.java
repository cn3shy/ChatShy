package io.github.cn3shy.chatshy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * 整个测试 JVM 共用一个 embedded PostgreSQL（Zonky），
 * 每个用例通过独立 database 隔离，无需 Docker 或本机安装 PostgreSQL。
 */
public final class PgTestServer {
    public record TestDatabase(String name, String url) {
    }

    private static EmbeddedPostgres postgres;

    private PgTestServer() {
    }

    public static synchronized EmbeddedPostgres instance() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.start();
                EmbeddedPostgres started = postgres;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        started.close();
                    } catch (IOException ignored) {
                        // JVM 退出阶段忽略清理异常
                    }
                }));
            } catch (IOException e) {
                throw new IllegalStateException("无法启动 embedded PostgreSQL", e);
            }
        }
        return postgres;
    }

    public static TestDatabase createDatabase() {
        String name = "chatshy_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection conn = instance().getPostgresDatabase().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("创建测试数据库失败", e);
        }
        return new TestDatabase(name, "jdbc:postgresql://localhost:" + instance().getPort() + "/" + name);
    }

    public static void dropDatabase(TestDatabase database) {
        try (Connection conn = instance().getPostgresDatabase().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + database.name() + " WITH (FORCE)");
        } catch (SQLException e) {
            throw new IllegalStateException("删除测试数据库失败", e);
        }
    }
}
