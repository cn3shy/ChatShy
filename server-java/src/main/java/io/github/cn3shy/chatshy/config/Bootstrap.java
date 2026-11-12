package io.github.cn3shy.chatshy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.cn3shy.chatshy.core.SettingsService;
import io.github.cn3shy.chatshy.db.Migrations;
import jakarta.annotation.PostConstruct;

/** 启动引导：迁移 → 播种设置 → 管理员密码 / 调用方 Key。 */
@Component
public class Bootstrap {
    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final Migrations migrations;
    private final SettingsService settings;
    private final ChatShyProperties properties;

    public Bootstrap(Migrations migrations, SettingsService settings, ChatShyProperties properties) {
        this.migrations = migrations;
        this.settings = settings;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        migrations.migrate();
        settings.initSettings(properties.defaultTimeoutMs(), properties.modelNameOr("chatshy"));
        bootstrapAdminPassword();
        bootstrapCallerKey();
    }

    private void bootstrapAdminPassword() {
        SettingsService.AdminBootstrap result = settings.ensureAdminPassword(properties.adminPasswordOrNull());
        if ("generated".equals(result.source())) {
            log.info("");
            log.info("════════════════════════════════════════════════");
            log.info("  ChatShy 管理员初始密码（仅本次生成时显示）：");
            log.info("    {}", result.password());
            log.info("  请尽快登录操作台修改。");
            log.info("════════════════════════════════════════════════");
            log.info("");
        } else if ("env".equals(result.source())) {
            log.info("[chatshy] 已使用 ADMIN_PASSWORD 环境变量初始化管理员密码");
        }
    }

    private void bootstrapCallerKey() {
        SettingsService.CallerKeyBootstrap result = settings.ensureCallerKey(properties.callerKeyOrNull());
        if ("generated".equals(result.source())) {
            log.info("");
            log.info("════════════════════════════════════════════════");
            log.info("  ChatShy 调用方 Key（所有调用方共用，仅本次生成时打印）：");
            log.info("    {}", result.key());
            log.info("  也可随时在操作台「设置」页查看或重新生成。");
            log.info("════════════════════════════════════════════════");
            log.info("");
        } else if ("env".equals(sourceOf(result))) {
            log.info("[chatshy] 已使用 CALLER_KEY 环境变量初始化调用方 Key");
        }
    }

    private static String sourceOf(SettingsService.CallerKeyBootstrap result) {
        return result.source();
    }
}
