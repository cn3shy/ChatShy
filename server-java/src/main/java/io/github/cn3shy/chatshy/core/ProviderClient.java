package io.github.cn3shy.chatshy.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.ProviderStore.Provider;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ChatMessage;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.Prompt;

/** 调用 OpenAI 兼容上游；流式/非流式、空闲超时与错误提炼与 Node 版对齐。 */
@Service
public class ProviderClient {
    public static final long PROVIDER_TIMEOUT_MS = 60_000;

    public enum ErrorCode {
        UPSTREAM_TIMEOUT, UPSTREAM_ABORTED, UPSTREAM_UNREACHABLE,
        UPSTREAM_HTTP_ERROR, UPSTREAM_BAD_RESPONSE, UPSTREAM_EMPTY
    }

    public static class ProviderError extends RuntimeException {
        private final ErrorCode code;
        private final Integer status;

        public ProviderError(ErrorCode code, String message) {
            this(code, message, null);
        }

        public ProviderError(ErrorCode code, String message, Integer status) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public ErrorCode code() {
            return code;
        }

        public Integer status() {
            return status;
        }
    }

    /** 转发层用它中止在途上游请求（Job 结束/调用方断开时）。 */
    public static final class AbortHandle {
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private volatile InputStream body;
        private volatile CompletableFuture<?> pending;

        public boolean isAborted() {
            return aborted.get();
        }

        public void abort() {
            aborted.set(true);
            closeBody();
            CompletableFuture<?> future = pending;
            if (future != null) future.cancel(true);
        }

        void setPending(CompletableFuture<?> future) {
            this.pending = future;
        }

        void setBody(InputStream stream) {
            this.body = stream;
            if (aborted.get()) closeBody();
        }

        void closeBody() {
            InputStream stream = body;
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 连接已断开
                }
            }
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 不让带 Authorization 的请求跟随跳转，避免被重定向到意外主机
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chatshy-upstream-idle");
        t.setDaemon(true);
        return t;
    });

    /** 上游回报的 token 用量；-1 表示该字段未知（上游没报）。 */
    public record TokenUsage(long inputTokens, long outputTokens, long cacheReadTokens) {
        public static final TokenUsage UNKNOWN = new TokenUsage(-1, -1, -1);

        public boolean known() {
            return inputTokens >= 0 || outputTokens >= 0 || cacheReadTokens >= 0;
        }
    }

    /** 转发结果：内容 + 上游 usage（可能未知）。 */
    public record ProviderResult(String content, TokenUsage usage) {
        public static ProviderResult of(String content) {
            return new ProviderResult(content, TokenUsage.UNKNOWN);
        }
    }

    public ProviderResult callProvider(Provider provider, Prompt prompt, long timeoutMs, AbortHandle abort) {
        long budget = timeoutMs > 0 ? timeoutMs : PROVIDER_TIMEOUT_MS;
        HttpRequest request = request(provider, prompt, false)
                .timeout(Duration.ofMillis(budget))
                .build();
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        abort.setPending(future);

        HttpResponse<String> response;
        try {
            response = future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderError(ErrorCode.UPSTREAM_ABORTED, "上游调用已中止");
        } catch (CancellationException e) {
            throw new ProviderError(ErrorCode.UPSTREAM_ABORTED, "上游调用已中止");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                throw new ProviderError(ErrorCode.UPSTREAM_TIMEOUT,
                        "上游 " + Math.round(budget / 1000.0) + " 秒内没有响应");
            }
            if (abort.isAborted()) {
                throw new ProviderError(ErrorCode.UPSTREAM_ABORTED, "上游调用已中止");
            }
            String detail = cause instanceof ConnectException
                    ? sanitize(cause.getMessage(), 200)
                    : sanitize(cause == null ? "未知错误" : cause.getMessage(), 200);
            throw new ProviderError(ErrorCode.UPSTREAM_UNREACHABLE, "无法连接上游：" + detail);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ProviderError(ErrorCode.UPSTREAM_HTTP_ERROR,
                    upstreamErrorMessage(response.statusCode(), response.body()), response.statusCode());
        }
        JsonNode data;
        try {
            data = Json.parse(response.body());
        } catch (Exception e) {
            throw new ProviderError(ErrorCode.UPSTREAM_BAD_RESPONSE,
                    "上游返回的不是 JSON（检查接口地址是否指向 OpenAI 兼容服务）");
        }
        return new ProviderResult(completionFromParsed(data), usageFromJson(data));
    }

    public ProviderResult callProviderStream(Provider provider, Prompt prompt, long timeoutMs,
                                             AbortHandle abort, Consumer<String> onDelta) {
        long budget = timeoutMs > 0 ? timeoutMs : PROVIDER_TIMEOUT_MS;
        AtomicBoolean idleTimedOut = new AtomicBoolean(false);
        ScheduledFuture<?>[] idleTask = new ScheduledFuture<?>[1];
        Runnable armIdle = () -> {
            if (idleTask[0] != null) idleTask[0].cancel(false);
            idleTask[0] = watchdog.schedule(() -> {
                idleTimedOut.set(true);
                abort.abort();
            }, budget, TimeUnit.MILLISECONDS);
        };

        HttpRequest request = request(provider, prompt, true).build();
        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        abort.setPending(future);
        armIdle.run();

        try {
            HttpResponse<InputStream> response;
            try {
                response = future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderError(ErrorCode.UPSTREAM_ABORTED, "上游调用已中止");
            } catch (CancellationException e) {
                throw abortError(idleTimedOut, budget);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof HttpTimeoutException) {
                    throw new ProviderError(ErrorCode.UPSTREAM_TIMEOUT,
                            "上游 " + Math.round(budget / 1000.0) + " 秒内没有响应");
                }
                if (abort.isAborted() || idleTimedOut.get()) {
                    throw abortError(idleTimedOut, budget);
                }
                String detail = sanitize(cause == null ? "未知错误" : cause.getMessage(), 200);
                throw new ProviderError(ErrorCode.UPSTREAM_UNREACHABLE, "无法连接上游：" + detail);
            }

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                String body = readAll(response.body());
                throw new ProviderError(ErrorCode.UPSTREAM_HTTP_ERROR, upstreamErrorMessage(status, body), status);
            }

            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            InputStream body = response.body();
            abort.setBody(body);

            // 有的"兼容"端点会忽略 stream=true 直接回 JSON：按非流式解析，再一次性下发
            if (contentType.contains("application/json")) {
                String text = readAll(body);
                String content = completionFromJson(text);
                onDelta.accept(content);
                return new ProviderResult(content, usageFromJson(Json.tryParse(text)));
            }

            StringBuilder content = new StringBuilder();
            TokenUsage usage = TokenUsage.UNKNOWN;
            boolean streamDone = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while (!streamDone && (line = reader.readLine()) != null) {
                    armIdle.run();
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    if (payload.equals("[DONE]")) {
                        streamDone = true;
                        break;
                    }
                    JsonNode data;
                    try {
                        data = Json.parse(payload);
                    } catch (Exception e) {
                        throw new ProviderError(ErrorCode.UPSTREAM_BAD_RESPONSE, "上游流式响应的数据不是合法 JSON");
                    }
                    String errorMessage = upstreamStreamError(data);
                    if (errorMessage != null) {
                        throw new ProviderError(ErrorCode.UPSTREAM_HTTP_ERROR, errorMessage);
                    }
                    // usage 帧通常出现在流末尾（含 include_usage 时）；持续取最后一个
                    TokenUsage chunkUsage = usageFromJson(data);
                    if (chunkUsage.known()) usage = chunkUsage;
                    JsonNode choices = data.get("choices");
                    if (choices != null && choices.isArray() && !choices.isEmpty()) {
                        JsonNode delta = choices.get(0).get("delta");
                        if (delta != null) {
                            String text = contentToString(delta.get("content"));
                            if (!text.isEmpty()) {
                                content.append(text);
                                onDelta.accept(text);
                            }
                        }
                    }
                }
            } catch (ProviderError e) {
                throw e;
            } catch (IOException e) {
                if (idleTimedOut.get() || abort.isAborted()) throw abortError(idleTimedOut, budget);
                throw new ProviderError(ErrorCode.UPSTREAM_UNREACHABLE,
                        "读取上游流式响应失败：" + sanitize(e.getMessage(), 200));
            }

            if (content.toString().trim().isEmpty()) {
                throw new ProviderError(ErrorCode.UPSTREAM_EMPTY, "上游返回了空内容");
            }
            return new ProviderResult(content.toString(), usage);
        } finally {
            if (idleTask[0] != null) idleTask[0].cancel(false);
            abort.closeBody();
        }
    }

    private static ProviderError abortError(AtomicBoolean idleTimedOut, long budget) {
        if (idleTimedOut.get()) {
            return new ProviderError(ErrorCode.UPSTREAM_TIMEOUT,
                    "上游 " + Math.round(budget / 1000.0) + " 秒内没有新数据");
        }
        return new ProviderError(ErrorCode.UPSTREAM_ABORTED, "上游调用已中止");
    }

    private HttpRequest.Builder request(Provider provider, Prompt prompt, boolean stream) {
        Map<String, Object> body = Json.obj();
        body.put("model", provider.model());
        body.put("messages", providerMessages(prompt));
        body.put("stream", stream);
        if (stream) {
            // OpenAI 标准字段：让流末尾多一帧 usage。不认识的端点会忽略，不影响内容。
            body.put("stream_options", Map.of("include_usage", true));
        }

        return HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/chat/completions"))
                .header("content-type", "application/json")
                .header("accept", stream ? "text/event-stream" : "application/json")
                .header("authorization", "Bearer " + provider.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
    }

    public static List<Map<String, Object>> providerMessages(Prompt prompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (prompt.system() != null && !prompt.system().isEmpty()) {
            Map<String, Object> system = Json.obj();
            system.put("role", "system");
            system.put("content", prompt.system());
            messages.add(system);
        }
        for (ChatMessage message : prompt.messages()) {
            Map<String, Object> item = Json.obj();
            item.put("role", message.role());
            item.put("content", message.content());
            messages.add(item);
        }
        return messages;
    }

    private static String completionFromJson(String text) {
        JsonNode data;
        try {
            data = Json.parse(text);
        } catch (Exception e) {
            throw new ProviderError(ErrorCode.UPSTREAM_BAD_RESPONSE,
                    "上游返回的不是 JSON（检查接口地址是否指向 OpenAI 兼容服务）");
        }
        return completionFromParsed(data);
    }

    private static String completionFromParsed(JsonNode data) {
        JsonNode choices = data.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode message = choices.get(0).get("message");
            if (message != null) {
                String content = contentToString(message.get("content"));
                if (!content.trim().isEmpty()) return content;
            }
        }
        throw new ProviderError(ErrorCode.UPSTREAM_EMPTY, "上游返回了空内容");
    }

    /**
     * 从 OpenAI 兼容响应里解析 usage；缺失字段记 -1。
     * 缓存命中兼容两种风格：OpenAI 的 prompt_tokens_details.cached_tokens 与 DeepSeek 的
     * prompt_cache_hit_tokens。
     */
    static TokenUsage usageFromJson(JsonNode data) {
        if (data == null || !data.isObject()) return TokenUsage.UNKNOWN;
        JsonNode usage = data.get("usage");
        if (usage == null || !usage.isObject()) return TokenUsage.UNKNOWN;

        long input = usage.path("prompt_tokens").asLong(-1);
        if (input < 0) input = usage.path("input_tokens").asLong(-1);
        long output = usage.path("completion_tokens").asLong(-1);
        if (output < 0) output = usage.path("output_tokens").asLong(-1);

        long cacheRead = usage.path("prompt_tokens_details").path("cached_tokens").asLong(-1);
        if (cacheRead < 0) cacheRead = usage.path("prompt_cache_hit_tokens").asLong(-1);
        if (cacheRead < 0) cacheRead = usage.path("cache_read_input_tokens").asLong(-1);

        return new TokenUsage(input, output, cacheRead);
    }

    /** 上游响应里的 content 可能是字符串，也可能是 text parts 数组；非 text 部件忽略。 */
    private static String contentToString(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part != null && part.isObject() && "text".equals(part.path("type").asText())
                        && part.path("text").isTextual()) {
                    sb.append(part.path("text").asText());
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static String upstreamStreamError(JsonNode data) {
        if (data == null || !data.isObject()) return null;
        JsonNode raw = data.get("error");
        if (raw == null || raw.isNull()) return null;
        String message;
        if (raw.isTextual()) {
            message = raw.asText();
        } else if (raw.isObject() && raw.path("message").isTextual()) {
            message = raw.path("message").asText();
        } else {
            message = "上游在流式响应中返回错误";
        }
        return sanitize(message, 300);
    }

    private static String upstreamErrorMessage(int status, String body) {
        String text = sanitize(body, 4000);
        if (!text.isEmpty()) {
            try {
                JsonNode data = Json.parse(text);
                JsonNode raw = data == null ? null : data.get("error");
                if (raw != null && raw.isObject() && raw.path("message").isTextual()) {
                    return sanitize(raw.path("message").asText(), 300);
                }
                if (raw != null && raw.isTextual()) {
                    return sanitize(raw.asText(), 300);
                }
                if (data != null && data.path("message").isTextual()) {
                    return sanitize(data.path("message").asText(), 300);
                }
            } catch (Exception ignored) {
                // 不是 JSON，直接把截断后的响应体当错误信息
            }
        }
        return text.isEmpty() ? "HTTP " + status : "HTTP " + status + "：" + sanitize(text, 300);
    }

    private static String sanitize(String text, int max) {
        if (text == null) return "";
        String cleaned = text.replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("(?U)\\s+", " ")
                .trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
    }

    private static String readAll(InputStream stream) {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ProviderError(ErrorCode.UPSTREAM_UNREACHABLE,
                    "读取上游响应失败：" + sanitize(e.getMessage(), 200));
        }
    }
}
