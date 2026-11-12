package io.github.cn3shy.chatshy.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.config.ChatShyProperties;
import io.github.cn3shy.chatshy.config.PersonaPrompt;
import io.github.cn3shy.chatshy.core.HttpUtil;
import io.github.cn3shy.chatshy.core.Json;
import io.github.cn3shy.chatshy.core.SensitiveWordFilter;
import io.github.cn3shy.chatshy.core.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 豆奶页面的同源后端：把浏览器请求转发到固定的 ChatShy 网关（默认本机 8088），
 * 由服务端注入调用方 Key 与身份头。浏览器永远看不到 Key。
 */
@RestController
public class DounaiController {
    private static final Logger log = LoggerFactory.getLogger(DounaiController.class);
    private static final int BODY_LIMIT = 2 * 1024 * 1024;

    private final SettingsService settings;
    private final ChatShyProperties properties;
    private final PersonaPrompt persona;
    private final SensitiveWordFilter sensitiveWords;
    private final int serverPort;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public DounaiController(SettingsService settings, ChatShyProperties properties,
                            PersonaPrompt persona, SensitiveWordFilter sensitiveWords,
                            @Value("${server.port:8088}") int serverPort) {
        this.settings = settings;
        this.properties = properties;
        this.persona = persona;
        this.sensitiveWords = sensitiveWords;
        this.serverPort = serverPort;
    }

    @GetMapping("/api/config")
    public ResponseEntity<?> config(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.ok(Map.of("model", settings.getModelName()));
    }

    @PostMapping("/api/chat")
    public void chat(@RequestBody(required = false) byte[] rawBody,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (rawBody != null && rawBody.length > BODY_LIMIT) {
            HttpUtil.writeJson(response, 413, error("请求体过大"));
            return;
        }
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        if (body == null || !body.isObject()) {
            HttpUtil.writeJson(response, 400, error("messages 不能为空"));
            return;
        }
        JsonNode messages = body.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            HttpUtil.writeJson(response, 400, error("messages 不能为空"));
            return;
        }

        // 与前端双端拦截重复内容：最新一条用户消息不允许与上一条用户消息完全相同
        String previousUserContent = null;
        String lastUserContent = null;
        for (JsonNode message : messages) {
            if (!"user".equals(message.path("role").asText())) continue;
            previousUserContent = lastUserContent;
            JsonNode content = message.path("content");
            lastUserContent = content.isTextual() ? content.asText() : null;
        }
        if (lastUserContent != null && lastUserContent.equals(previousUserContent)) {
            HttpUtil.writeJson(response, 400, error("不能重复发送相同的内容"));
            return;
        }

        // 垃圾话直接不回复：不请求上游、不创建会话、不消耗 token
        String hit = sensitiveWords.match(lastUserContent);
        if (hit != null) {
            log.warn("拦截敏感词：ip={} word={}", HttpUtil.clientIp(request), hit);
            writeBlocked(response);
            return;
        }

        String callerKey = settings.getCallerKey();
        if (callerKey == null || callerKey.isEmpty()) {
            HttpUtil.writeJson(response, 503, error("服务端尚未配置调用方 Key"));
            return;
        }

        String baseUrl = properties.dounai().upstreamBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:" + serverPort + "/v1";
        }
        String endpoint = buildEndpoint(baseUrl, "/chat/completions");

        List<Object> payloadMessages = new ArrayList<>();
        // 人设只由服务端维护：客户端传来的 system/developer 一律丢弃，防止网络层覆盖人设
        String systemPrompt = persona.text();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Map<String, Object> system = Json.obj();
            system.put("role", "system");
            system.put("content", systemPrompt);
            payloadMessages.add(system);
        }
        boolean hasUserMessage = false;
        for (JsonNode message : messages) {
            String role = message.path("role").asText();
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            if ("user".equals(role)) hasUserMessage = true;
            payloadMessages.add(message);
        }
        if (!hasUserMessage) {
            HttpUtil.writeJson(response, 400, error("messages 不能为空"));
            return;
        }

        Map<String, Object> payload = Json.obj();
        payload.put("model", settings.getModelName());
        payload.put("messages", payloadMessages);
        payload.put("stream", true);
        JsonNode temperature = body.get("temperature");
        if (temperature != null && temperature.isNumber()) {
            payload.put("temperature", temperature.asDouble());
        }

        HttpRequest upstreamRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + callerKey)
                // 把访客真实 IP 与浏览器 UA 透传给网关，工作台才能看到访客来源
                .header("X-Forwarded-For", HttpUtil.clientIp(request))
                .header("User-Agent", request.getHeader("User-Agent") == null
                        ? "unknown" : request.getHeader("User-Agent"))
                .header("X-ChatShy-Caller", HttpUtil.encodeCallerHeader(
                        properties.dounai().caller() == null ? "豆奶" : properties.dounai().caller()))
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> upstream;
        try {
            upstream = http.sendAsync(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HttpUtil.writeJson(response, 502, error("连接上游失败：请求被中断"));
            return;
        } catch (ExecutionException e) {
            String detail = e.getCause() == null ? "未知错误" : e.getCause().getMessage();
            HttpUtil.writeJson(response, 502, error("连接上游失败：" + detail + "（地址：" + endpoint + "）"));
            return;
        }

        int status = upstream.statusCode();
        if (status < 200 || status >= 300) {
            String text;
            try (InputStream in = upstream.body()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String message = text;
            try {
                JsonNode errorNode = Json.parse(text).path("error");
                if (errorNode.isObject() && errorNode.path("message").isTextual()) {
                    message = errorNode.path("message").asText();
                }
            } catch (Exception ignored) {
                // 保留原文
            }
            if (message.length() > 800) message = message.substring(0, 800);
            HttpUtil.writeJson(response, status, error("上游返回 " + status + "：" + (message == null ? "" : message)));
            return;
        }

        String upstreamType = upstream.headers().firstValue("content-type").orElse("").toLowerCase();
        if (!upstreamType.contains("text/event-stream")) {
            String text;
            try (InputStream in = upstream.body()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            response.setStatus(200);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("text/event-stream; charset=utf-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
            OutputStream out = response.getOutputStream();
            out.write(("data: " + text.trim() + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        try (InputStream in = upstream.body()) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // 浏览器断开：关闭上游连接即可，ChatShy 侧会检测到并收尾
        }
    }

    /** 命中敏感词时返回静默 SSE：前端识别响应头后移除占位消息，不展示任何回复。 */
    private static void writeBlocked(HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-ChatShy-Blocked", "1");
        OutputStream out = response.getOutputStream();
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> error = Json.obj();
        error.put("message", message);
        Map<String, Object> body = Json.obj();
        body.put("error", error);
        return body;
    }

    /** 与 Node 版 buildEndpoint 归一：已含版本段直接拼，否则补 /v1。 */
    static String buildEndpoint(String raw, String suffix) {
        String base = raw.trim().replaceAll("/+$", "")
                .replaceAll("(?i)/chat/completions$", "")
                .replaceAll("(?i)/models$", "")
                .replaceAll("/+$", "");
        if (base.matches("(?i).*/v\\d+$")) return base + suffix;
        return base + "/v1" + suffix;
    }
}
