package io.github.cn3shy.chatshy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON 小工具：节点形状与 Node 版保持一致的 LinkedHashMap 构造器。 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    public static List<Object> arr() {
        return new ArrayList<>();
    }

    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败", e);
        }
    }

    public static JsonNode parse(String raw) throws JsonProcessingException {
        return MAPPER.readTree(raw);
    }

    public static JsonNode tryParse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static String utf8(byte[] raw) {
        return raw == null ? "" : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }
}
