package io.github.cn3shy.chatshy.core;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** settings 表的访问层，键名与 Node 版一致。 */
@Service
public class SettingsService {
    public static final String DEFAULT_TIMEOUT_MS = "default_timeout_ms";
    public static final String MODEL_NAME = "model_name";
    public static final String ADMIN_PASSWORD_HASH = "admin_password_hash";
    public static final String CALLER_KEY = "caller_key";
    public static final String AUTO_FORWARD_ENABLED = "auto_forward_enabled";
    public static final String AUTO_FORWARD_PROVIDER_ID = "auto_forward_provider_id";
    public static final String SENSITIVE_WORDS = "sensitive_words";
    public static final String SENSITIVE_FILTER_ENABLED = "sensitive_filter_enabled";

    private final JdbcTemplate jdbc;

    public SettingsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String getSetting(String key) {
        List<String> rows = jdbc.query("SELECT value FROM settings WHERE key = ?",
                (rs, i) -> rs.getString(1), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void setSetting(String key, String value) {
        jdbc.update("INSERT INTO settings (key, value) VALUES (?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
    }

    /** 首次启动播种默认值；已有值不覆盖。 */
    public void initSettings(long defaultTimeoutMs, String modelName) {
        jdbc.update("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                DEFAULT_TIMEOUT_MS, String.valueOf(defaultTimeoutMs));
        jdbc.update("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                MODEL_NAME, modelName);
    }

    public long getDefaultTimeoutMs() {
        return getDefaultTimeoutMs(180_000L);
    }

    public long getDefaultTimeoutMs(long fallback) {
        String raw = getSetting(DEFAULT_TIMEOUT_MS);
        if (raw == null) return fallback;
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String getModelName() {
        return getModelName("chatshy");
    }

    public String getModelName(String fallback) {
        String value = getSetting(MODEL_NAME);
        return value == null ? fallback : value;
    }

    public boolean isAutoForwardEnabled() {
        return "1".equals(getSetting(AUTO_FORWARD_ENABLED));
    }

    public String getAutoForwardProviderId() {
        String value = getSetting(AUTO_FORWARD_PROVIDER_ID);
        return value == null ? "" : value;
    }

    public void setAutoForwardEnabled(boolean enabled) {
        setSetting(AUTO_FORWARD_ENABLED, enabled ? "1" : "0");
    }

    public void setAutoForwardProviderId(String providerId) {
        setSetting(AUTO_FORWARD_PROVIDER_ID, providerId);
    }

    public String getCallerKey() {
        return getSetting(CALLER_KEY);
    }

    /** 敏感词过滤默认开启；只有显式设为 0 才关闭。 */
    public boolean isSensitiveFilterEnabled() {
        return !"0".equals(getSetting(SENSITIVE_FILTER_ENABLED));
    }

    public void setSensitiveFilterEnabled(boolean enabled) {
        setSetting(SENSITIVE_FILTER_ENABLED, enabled ? "1" : "0");
    }

    /** 操作台配置的敏感词原文；null/空串表示使用内置默认词库。 */
    public String getSensitiveWordsText() {
        return getSetting(SENSITIVE_WORDS);
    }

    public void setSensitiveWordsText(String text) {
        setSetting(SENSITIVE_WORDS, text == null ? "" : text);
    }

    public String regenerateCallerKey() {
        String key = Passwords.newCallerKey();
        setSetting(CALLER_KEY, key);
        return key;
    }

    public boolean verifyAdminPassword(String password) {
        String stored = getSetting(ADMIN_PASSWORD_HASH);
        return stored != null && Passwords.verifyPassword(password, stored);
    }

    public void setAdminPassword(String password) {
        setSetting(ADMIN_PASSWORD_HASH, Passwords.hashPassword(password));
    }

    public record AdminBootstrap(String source, String password) {
    }

    public record CallerKeyBootstrap(String source, String key) {
    }

    /** source: env / generated / null（已有，不动） */
    public AdminBootstrap ensureAdminPassword(String fromEnv) {
        if (getSetting(ADMIN_PASSWORD_HASH) != null) return new AdminBootstrap(null, null);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            setAdminPassword(fromEnv);
            return new AdminBootstrap("env", null);
        }
        String password = Passwords.newAdminPassword();
        setAdminPassword(password);
        return new AdminBootstrap("generated", password);
    }

    /** source: env / generated / existing */
    public CallerKeyBootstrap ensureCallerKey(String fromEnv) {
        if (getCallerKey() != null) return new CallerKeyBootstrap("existing", null);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            setSetting(CALLER_KEY, fromEnv);
            return new CallerKeyBootstrap("env", null);
        }
        String key = regenerateCallerKey();
        return new CallerKeyBootstrap("generated", key);
    }

    public boolean callerKeyMatches(String expected, String provided) {
        return expected != null && provided != null && Passwords.constantTimeEquals(expected, provided);
    }
}
