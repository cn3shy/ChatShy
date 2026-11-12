package io.github.cn3shy.chatshy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 与 Node 版 .env 对齐的配置。空字符串表示"未设置"，由启动引导逻辑决定是否随机生成。
 */
@ConfigurationProperties(prefix = "chatshy")
public record ChatShyProperties(
        String adminPassword,
        String callerKey,
        long defaultTimeoutMs,
        String modelName,
        long ssePingMs,
        long hubPingMs,
        long typewriterTickMs,
        Dounai dounai) {

    public record Dounai(
            boolean enabled,
            int port,
            String host,
            String caller,
            String systemPrompt,
            String upstreamBaseUrl) {
    }

    public String adminPasswordOrNull() {
        return adminPassword == null || adminPassword.isEmpty() ? null : adminPassword;
    }

    public String callerKeyOrNull() {
        return callerKey == null || callerKey.isEmpty() ? null : callerKey;
    }

    public String modelNameOr(String fallback) {
        return modelName == null || modelName.isBlank() ? fallback : modelName;
    }
}
