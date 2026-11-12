package io.github.cn3shy.chatshy.protocol;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.Json;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ChatMessage;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ParseResult;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.Prompt;

/** OpenAI /v1/chat/completions 协议适配。纯函数，逐字段对齐 Node 版。 */
public final class OpenAiProtocol {
    private static final SecureRandom RANDOM = new SecureRandom();

    public record ParsedRequest(Prompt prompt, boolean stream, boolean includeUsage) {
    }

    public record CompletionParams(String id, long created, String model, String content,
                                   int promptTokens, int completionTokens, boolean includeUsage) {
    }

    private OpenAiProtocol() {
    }

    private static String unsupportedPart(String type) {
        return "[不支持的部件: " + type + "]";
    }

    public static String contentToString(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part != null && part.isObject() && part.path("type").isTextual()) {
                    String type = part.path("type").asText();
                    if (type.equals("text") && part.path("text").isTextual()) {
                        sb.append(part.path("text").asText());
                    } else {
                        sb.append(unsupportedPart(type));
                    }
                } else {
                    sb.append(unsupportedPart("unknown"));
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

        StringBuilder system = new StringBuilder();
        boolean hasSystem = false;
        java.util.ArrayList<ChatMessage> messages = new java.util.ArrayList<>();

        for (JsonNode raw : messagesNode) {
            if (raw == null || !raw.isObject()) {
                return ParseResult.fail("Invalid message entry");
            }
            String role = raw.path("role").isTextual() ? raw.path("role").asText() : "";
            String content = contentToString(raw.get("content"));
            JsonNode toolCalls = raw.get("tool_calls");
            if (content.isEmpty() && toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                content = unsupportedPart("tool_calls");
            }
            switch (role) {
                case "system", "developer" -> {
                    if (hasSystem) system.append("\n\n");
                    system.append(content);
                    hasSystem = true;
                }
                case "assistant" -> messages.add(new ChatMessage("assistant", content));
                case "user", "tool", "function" -> messages.add(new ChatMessage("user", content));
                default -> {
                    String label = role.isEmpty() ? "(missing)" : role;
                    return ParseResult.fail("Unsupported message role: " + label);
                }
            }
        }

        boolean includeUsage = false;
        JsonNode streamOptions = body.get("stream_options");
        if (streamOptions != null && streamOptions.isObject()) {
            JsonNode include = streamOptions.get("include_usage");
            includeUsage = include != null && include.isBoolean() && include.asBoolean();
        }
        boolean stream = body.path("stream").isBoolean() && body.path("stream").asBoolean();
        String model = body.path("model").isTextual() ? body.path("model").asText() : "";
        Prompt prompt = new Prompt(model, hasSystem ? system.toString() : null, List.copyOf(messages));
        return ParseResult.ok(new ParsedRequest(prompt, stream, includeUsage));
    }

    // ---- 渲染 ----

    public static String newCompletionId() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "chatcmpl-" + HexFormat.of().formatHex(bytes);
    }

    public static String sseFrame(Object data) {
        return "data: " + Json.stringify(data) + "\n\n";
    }

    public static final String SSE_DONE = "data: [DONE]\n\n";
    public static final String SSE_PING_COMMENT = ": ping\n\n";

    private static Map<String, Object> usageOf(CompletionParams p) {
        Map<String, Object> usage = Json.obj();
        usage.put("prompt_tokens", p.promptTokens());
        usage.put("completion_tokens", p.completionTokens());
        usage.put("total_tokens", p.promptTokens() + p.completionTokens());
        return usage;
    }

    private static Map<String, Object> chunk(CompletionParams p, List<Object> choices) {
        Map<String, Object> chunk = Json.obj();
        chunk.put("id", p.id());
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", p.created());
        chunk.put("model", p.model());
        chunk.put("choices", choices);
        return chunk;
    }

    public static Map<String, Object> renderCompletion(CompletionParams p) {
        Map<String, Object> message = Json.obj();
        message.put("role", "assistant");
        message.put("content", p.content());

        Map<String, Object> choice = Json.obj();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");

        Map<String, Object> body = Json.obj();
        body.put("id", p.id());
        body.put("object", "chat.completion");
        body.put("created", p.created());
        body.put("model", p.model());
        body.put("choices", List.of(choice));
        body.put("usage", usageOf(p));
        return body;
    }

    public static String completionRoleFrame(String id, long created, String model) {
        Map<String, Object> delta = Json.obj();
        delta.put("role", "assistant");
        delta.put("content", "");

        Map<String, Object> choice = Json.obj();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", null);

        Map<String, Object> frame = Json.obj();
        frame.put("id", id);
        frame.put("object", "chat.completion.chunk");
        frame.put("created", created);
        frame.put("model", model);
        frame.put("choices", List.of(choice));
        return sseFrame(frame);
    }

    public static String completionDeltaFrame(String id, long created, String model, String text) {
        Map<String, Object> delta = Json.obj();
        delta.put("content", text);

        Map<String, Object> choice = Json.obj();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", null);

        Map<String, Object> frame = Json.obj();
        frame.put("id", id);
        frame.put("object", "chat.completion.chunk");
        frame.put("created", created);
        frame.put("model", model);
        frame.put("choices", List.of(choice));
        return sseFrame(frame);
    }

    public static List<String> completionFinishFrames(CompletionParams p) {
        Map<String, Object> choice = Json.obj();
        choice.put("index", 0);
        choice.put("delta", Json.obj());
        choice.put("finish_reason", "stop");

        java.util.ArrayList<String> frames = new java.util.ArrayList<>();
        frames.add(sseFrame(chunk(p, List.of(choice))));
        if (p.includeUsage()) {
            Map<String, Object> usageChunk = chunk(p, List.of());
            usageChunk.put("usage", usageOf(p));
            frames.add(sseFrame(usageChunk));
        }
        frames.add(SSE_DONE);
        return frames;
    }

    public static Map<String, Object> renderModel(String modelName, long created) {
        Map<String, Object> model = Json.obj();
        model.put("id", modelName);
        model.put("object", "model");
        model.put("created", created);
        model.put("owned_by", "chatshy");
        return model;
    }

    public static Map<String, Object> renderModels(String modelName, long created) {
        Map<String, Object> body = Json.obj();
        body.put("object", "list");
        body.put("data", List.of(renderModel(modelName, created)));
        return body;
    }

    public static Map<String, Object> error(String message, String type, String code) {
        Map<String, Object> error = Json.obj();
        error.put("message", message);
        error.put("type", type);
        error.put("param", null);
        error.put("code", code);

        Map<String, Object> body = Json.obj();
        body.put("error", error);
        return body;
    }

    public static Map<String, Object> invalidKey() {
        return error("Incorrect API key provided", "invalid_request_error", "invalid_api_key");
    }

    public static Map<String, Object> badRequest(String message) {
        return error(message, "invalid_request_error", null);
    }

    public static Map<String, Object> timeout() {
        return error("The model timed out while generating a response.", "server_error", "timeout");
    }

    public static Map<String, Object> upstreamFailure() {
        return error("The upstream model failed while generating a response.", "server_error", "upstream_error");
    }
}
