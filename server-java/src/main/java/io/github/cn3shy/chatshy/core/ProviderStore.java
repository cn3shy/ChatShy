package io.github.cn3shy.chatshy.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** 第三方 AI 厂商配置（OpenAI 兼容 /chat/completions）。 */
@Service
public class ProviderStore {
    public record Provider(String id, String name, String baseUrl, String apiKey, String model,
                           long createdAt, long updatedAt) {
    }

    public record PublicProvider(String id, String name, String baseUrl, String model,
                                 boolean hasApiKey, String apiKeyMasked, long createdAt, long updatedAt) {
    }

    public record ProviderInput(String name, String baseUrl, String apiKey, String model) {
    }

    /** null = 字段缺省（保持不变）；apiKey 的空串 = 沿用旧 Key。 */
    public record ProviderPatch(String name, String baseUrl, String apiKey, String model) {
    }

    public static class ProviderValidationException extends RuntimeException {
        private final String code;

        public ProviderValidationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final int NAME_MAX = 64;
    private static final int URL_MAX = 2048;
    private static final int MODEL_MAX = 200;
    private static final int KEY_MAX = 4096;

    private static final RowMapper<Provider> MAPPER = (rs, i) -> new Provider(
            rs.getString("id"), rs.getString("name"), rs.getString("base_url"), rs.getString("api_key"),
            rs.getString("model"), rs.getLong("created_at"), rs.getLong("updated_at"));

    private final JdbcTemplate jdbc;

    public ProviderStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Provider> listProviders() {
        return jdbc.query("SELECT * FROM providers ORDER BY created_at ASC, seq ASC", MAPPER);
    }

    public Provider getProvider(String id) {
        List<Provider> rows = jdbc.query("SELECT * FROM providers WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Provider createProvider(ProviderInput input) {
        String name = normalizeName(input.name());
        String baseUrl = normalizeBaseUrl(input.baseUrl());
        String apiKey = normalizeApiKey(input.apiKey());
        String model = normalizeModel(input.model());
        assertNameFree(name, null);
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO providers (id, name, base_url, api_key, model, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, name, baseUrl, apiKey, model, now, now);
        return getProvider(id);
    }

    public Provider updateProvider(String id, ProviderPatch patch) {
        Provider current = getProvider(id);
        if (current == null) return null;
        String name = patch.name() == null ? current.name() : normalizeName(patch.name());
        String baseUrl = patch.baseUrl() == null ? current.baseUrl() : normalizeBaseUrl(patch.baseUrl());
        String model = patch.model() == null ? current.model() : normalizeModel(patch.model());
        String apiKey = patch.apiKey() == null || patch.apiKey().isEmpty()
                ? current.apiKey() : normalizeApiKey(patch.apiKey());
        assertNameFree(name, id);
        jdbc.update("UPDATE providers SET name = ?, base_url = ?, api_key = ?, model = ?, updated_at = ? WHERE id = ?",
                name, baseUrl, apiKey, model, System.currentTimeMillis(), id);
        return getProvider(id);
    }

    public boolean deleteProvider(String id) {
        return jdbc.update("DELETE FROM providers WHERE id = ?", id) > 0;
    }

    public static String maskApiKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "••••••••";
        return "••••••••" + key.substring(key.length() - 4);
    }

    public static PublicProvider toPublic(Provider provider) {
        return new PublicProvider(provider.id(), provider.name(), provider.baseUrl(), provider.model(),
                provider.apiKey() != null && !provider.apiKey().isEmpty(),
                maskApiKey(provider.apiKey()), provider.createdAt(), provider.updatedAt());
    }

    private void assertNameFree(String name, String exceptId) {
        List<String> ids = jdbc.query("SELECT id FROM providers WHERE name = ?",
                (rs, i) -> rs.getString(1), name);
        if (!ids.isEmpty() && (exceptId == null || !ids.contains(exceptId))) {
            throw new ProviderValidationException("provider_exists", "已存在名为「" + name + "」的厂商配置");
        }
    }

    private static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("(?U)\\s+", " ").trim();
        if (name.isEmpty()) throw new ProviderValidationException("invalid_name", "厂商名称不能为空");
        if (name.codePointCount(0, name.length()) > NAME_MAX) {
            throw new ProviderValidationException("invalid_name", "厂商名称不能超过 " + NAME_MAX + " 个字符");
        }
        return name;
    }

    private static String normalizeModel(String raw) {
        String model = raw == null ? "" : raw.trim();
        if (model.isEmpty()) throw new ProviderValidationException("invalid_model", "模型名不能为空");
        if (model.length() > MODEL_MAX) {
            throw new ProviderValidationException("invalid_model", "模型名不能超过 " + MODEL_MAX + " 个字符");
        }
        return model;
    }

    private static String normalizeApiKey(String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.isEmpty()) throw new ProviderValidationException("invalid_api_key", "API Key 不能为空");
        if (key.length() > KEY_MAX) {
            throw new ProviderValidationException("invalid_api_key", "API Key 不能超过 " + KEY_MAX + " 个字符");
        }
        return key;
    }

    public static String normalizeBaseUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException e) {
            throw new ProviderValidationException("invalid_base_url", "接口地址不是合法的 URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null) {
            throw new ProviderValidationException("invalid_base_url", "接口地址不是合法的 URL");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ProviderValidationException("invalid_base_url", "接口地址只支持 http/https");
        }
        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isEmpty()) {
            throw new ProviderValidationException("invalid_base_url", "接口地址里不能带用户名/密码");
        }
        if ((uri.getRawQuery() != null && !uri.getRawQuery().isEmpty())
                || (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty())) {
            throw new ProviderValidationException("invalid_base_url", "接口地址里不能带查询参数或锚点");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        int port = uri.getPort();
        int defaultPort = scheme.equals("https") ? 443 : 80;
        String authority = scheme + "://" + host + (port != -1 && port != defaultPort ? ":" + port : "");

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        path = path.replaceAll("/+$", "");
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) {
            path = path.substring(0, path.length() - "/chat/completions".length()).replaceAll("/+$", "");
        }
        String normalized = authority + path;
        if (normalized.length() > URL_MAX) {
            throw new ProviderValidationException("invalid_base_url", "接口地址不能超过 " + URL_MAX + " 个字符");
        }
        return normalized;
    }
}
