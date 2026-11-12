package io.github.cn3shy.chatshy.protocol;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.Json;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ChatMessage;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ParseResult;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.Prompt;

/** Anthropic /v1/messages 协议适配。纯函数，逐字段对齐 Node 版。 */
public final class AnthropicProtocol {
    private static final SecureRandom RANDOM = new SecureRandom();

    public record ParsedRequest(Prompt prompt, boolean stream) {
    }

    public record MessageParams(String id, String model, String content, int inputTokens, int outputTokens) {
    }

    private AnthropicProtocol() {
    }

    private static String unsupportedBlock(String type) {
        return "[不支持的块类型: " + type + "]";
    }

    public static String blocksToString(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block != null && block.isObject() && block.path("type").isTextual()) {
                    String type = block.path("type").asText();
                    if (type.equals("text") && block.path("text").isTextual()) {
                        sb.append(block.path("text").asText());
                    } else {
                        sb.append(unsupportedBlock(type));
                    }
                } else {
                    sb.append(unsupportedBlock("unknown"));
                }
            }
            return sb.toString();
        }
        if (content.isObject()) return "[object Object]";
        return content.asText();
    }

    public static ParseResult<ParsedRequest> parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            return ParseResult.fail("Invalid request body");
        }
        JsonNode messagesNode = body.get("messages");
        if (messagesNode == null || !messagesNode.isArray() || messagesNode.isEmpty()) {
            return ParseResult.fail("'messages' must be a non-empty array");
        }

        ArrayList<ChatMessage> messages = new ArrayList<>();
        ArrayList<String> inlineSystem = new ArrayList<>();

        for (JsonNode raw : messagesNode) {
            if (raw == null || !raw.isObject()) {
                return ParseResult.fail("Invalid message entry");
            }
            JsonNode roleNode = raw.get("role");
            String role = roleNode != null && roleNode.isTextual() ? roleNode.asText() : null;
            if ("system".equals(role)) {
                inlineSystem.add(blocksToString(raw.get("content")));
                continue;
            }
            if (!"user".equals(role) && !"assistant".equals(role)) {
                String label = role == null ? "(missing)" : role;
                return ParseResult.fail("Unsupported message role: " + label);
            }
            messages.add(new ChatMessage(role, blocksToString(raw.get("content"))));
        }

        String system = null;
        JsonNode systemNode = body.get("system");
        if (systemNode != null && systemNode.isTextual() && !systemNode.asText().isEmpty()) {
            system = systemNode.asText();
        } else if (systemNode != null && systemNode.isArray()) {
            String s = blocksToString(systemNode);
            if (!s.isEmpty()) system = s;
        }
        if (!inlineSystem.isEmpty()) {
            StringBuilder extra = new StringBuilder();
            for (String part : inlineSystem) {
                if (part.isEmpty()) continue;
                if (extra.length() > 0) extra.append("\n\n");
                extra.append(part);
            }
            if (extra.length() > 0) {
                system = system == null ? extra.toString() : system + "\n\n" + extra;
            }
        }

        String model = body.path("model").isTextual() ? body.path("model").asText() : "";
        boolean stream = body.path("stream").isBoolean() && body.path("stream").asBoolean();
        return ParseResult.ok(new ParsedRequest(new Prompt(model, system, List.copyOf(messages)), stream));
    }

    // ---- 渲染 ----

    public static String newMessageId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "msg_" + HexFormat.of().formatHex(bytes);
    }

    public static String eventFrame(String event, Object data) {
        return "event: " + event + "\ndata: " + Json.stringify(data) + "\n\n";
    }

    public static String pingFrame() {
        Map<String, Object> data = Json.obj();
        data.put("type", "ping");
        return eventFrame("ping", data);
    }

    private static Map<String, Object> usage(int inputTokens, int outputTokens) {
        Map<String, Object> usage = Json.obj();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        usage.put("cache_creation_input_tokens", 0);
        usage.put("cache_read_input_tokens", 0);
        return usage;
    }

    public static Map<String, Object> renderMessage(MessageParams p) {
        Map<String, Object> text = Json.obj();
        text.put("type", "text");
        text.put("text", p.content());

        Map<String, Object> body = Json.obj();
        body.put("id", p.id());
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("model", p.model());
        body.put("content", List.of(text));
        body.put("stop_reason", "end_turn");
        body.put("stop_sequence", null);
        body.put("usage", usage(p.inputTokens(), p.outputTokens()));
        return body;
    }

    public static String messageStartFrame(String id, String model, int inputTokens) {
        Map<String, Object> message = Json.obj();
        message.put("id", id);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", List.of());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", usage(inputTokens, 0));

        Map<String, Object> data = Json.obj();
        data.put("type", "message_start");
        data.put("message", message);
        return eventFrame("message_start", data);
    }

    public static String messageOpenBlockFrame() {
        Map<String, Object> block = Json.obj();
        block.put("type", "text");
        block.put("text", "");

        Map<String, Object> data = Json.obj();
        data.put("type", "content_block_start");
        data.put("index", 0);
        data.put("content_block", block);
        return eventFrame("content_block_start", data);
    }

    public static String messageDeltaFrame(String text) {
        Map<String, Object> delta = Json.obj();
        delta.put("type", "text_delta");
        delta.put("text", text);

        Map<String, Object> data = Json.obj();
        data.put("type", "content_block_delta");
        data.put("index", 0);
        data.put("delta", delta);
        return eventFrame("content_block_delta", data);
    }

    public static List<String> messageFinishFrames(MessageParams p) {
        Map<String, Object> stop = Json.obj();
        stop.put("type", "content_block_stop");
        stop.put("index", 0);

        Map<String, Object> delta = Json.obj();
        delta.put("stop_reason", "end_turn");
        delta.put("stop_sequence", null);

        Map<String, Object> messageDelta = Json.obj();
        messageDelta.put("type", "message_delta");
        messageDelta.put("delta", delta);
        messageDelta.put("usage", Map.of("output_tokens", p.outputTokens()));

        Map<String, Object> messageStop = Json.obj();
        messageStop.put("type", "message_stop");

        return List.of(
                eventFrame("content_block_stop", stop),
                eventFrame("message_delta", messageDelta),
                eventFrame("message_stop", messageStop));
    }

    public static Map<String, Object> error(String type, String message) {
        Map<String, Object> error = Json.obj();
        error.put("type", type);
        error.put("message", message);

        Map<String, Object> body = Json.obj();
        body.put("type", "error");
        body.put("error", error);
        return body;
    }

    public static Map<String, Object> invalidKey() {
        return error("authentication_error", "invalid x-api-key");
    }

    public static Map<String, Object> badRequest(String message) {
        return error("invalid_request_error", message);
    }

    public static Map<String, Object> timeout() {
        return error("overloaded_error", "Overloaded");
    }

    public static Map<String, Object> upstreamFailure() {
        return error("api_error", "Upstream model failed while generating a response.");
    }

    public static Map<String, Object> blacklisted() {
        return error("invalid_request_error", "该会话已被拉黑，无法继续发送消息");
    }
}
