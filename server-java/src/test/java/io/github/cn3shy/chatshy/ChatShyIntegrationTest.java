package io.github.cn3shy.chatshy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.JobQueue;
import io.github.cn3shy.chatshy.core.Json;
import io.github.cn3shy.chatshy.core.PendingJob;
import io.github.cn3shy.chatshy.core.SettingsService;
import io.github.cn3shy.chatshy.core.ThreadStore;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatShyIntegrationTest {
    private static final String CALLER_KEY = "sk-chatshy-test-caller-key";
    private static final String ADMIN_PASSWORD = "test-admin-pass";
    private static PgTestServer.TestDatabase testDatabase;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        testDatabase = PgTestServer.createDatabase();
        registry.add("spring.datasource.url", testDatabase::url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("chatshy.admin-password", () -> ADMIN_PASSWORD);
        registry.add("chatshy.caller-key", () -> CALLER_KEY);
        registry.add("chatshy.dounai.enabled", () -> "false");
        registry.add("chatshy.sse-ping-ms", () -> "80");
        registry.add("chatshy.hub-ping-ms", () -> "150");
        registry.add("chatshy.typewriter-tick-ms", () -> "1");
        registry.add("chatshy.default-timeout-ms", () -> "60000");
        // 测试会连续创建大量会话，放宽 IP 限流，避免测试自己触发 429
        registry.add("chatshy.new-thread-limit", () -> "1000");
    }

    @LocalServerPort
    int port;

    @Autowired
    JobQueue queue;
    @Autowired
    ThreadStore threads;
    @Autowired
    SettingsService settings;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @AfterEach
    void cleanup() {
        for (PendingJob job : queue.listPending()) queue.cancel(job.id());
        settings.setAutoForwardEnabled(false);
        settings.setAutoForwardProviderId("");
        settings.setSetting(SettingsService.DEFAULT_TIMEOUT_MS, "60000");
    }

    @AfterAll
    void cleanupDatabase() {
        if (testDatabase != null) PgTestServer.dropDatabase(testDatabase);
    }

    // ---------------------------------------------------------------- helpers

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpResponse<String> call(String method, String path, String body, String cookie,
                                      String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "application/json");
        if (cookie != null) builder.header("cookie", cookie);
        for (int i = 0; i + 1 < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String login() throws Exception {
        HttpResponse<String> response = call("POST", "/admin/api/login",
                Json.stringify(Map.of("password", ADMIN_PASSWORD)), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }

    private HttpRequest.Builder callerRequest(String body, String ua, String caller) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url("/v1/chat/completions")))
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "application/json")
                .header("authorization", "Bearer " + CALLER_KEY)
                .header("user-agent", ua)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (caller != null) builder.header("x-chatshy-caller", caller);
        return builder;
    }

    private static String openAiBody(String content, boolean stream) {
        Map<String, Object> body = Json.obj();
        body.put("model", "gpt-test");
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("stream", stream);
        if (stream) body.put("stream_options", Map.of("include_usage", true));
        return Json.stringify(body);
    }

    private List<PendingJob> waitPending(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<PendingJob> pending = queue.listPending();
            if (pending.size() >= count) return pending;
            Thread.sleep(10);
        }
        fail("等待挂起请求超时");
        return List.of();
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("等待条件超时");
            Thread.sleep(10);
        }
    }

    private HttpResponse<String> adminReply(String cookie, String jobId, String content) throws Exception {
        return call("POST", "/admin/api/jobs/" + jobId + "/reply",
                Json.stringify(Map.of("content", content)), cookie);
    }

    private JsonNode adminThreads(String cookie) throws Exception {
        HttpResponse<String> response = call("GET", "/admin/api/threads", null, cookie);
        assertThat(response.statusCode()).isEqualTo(200);
        return Json.parse(response.body());
    }

    private String createProvider(String cookie, String baseUrl) throws Exception {
        HttpResponse<String> response = call("POST", "/admin/api/providers", Json.stringify(Map.of(
                "name", "测试厂商-" + System.nanoTime(),
                "baseUrl", baseUrl,
                "apiKey", "test-upstream-key",
                "model", "upstream-model")), cookie);
        assertThat(response.statusCode()).isEqualTo(200);
        return Json.parse(response.body()).path("provider").path("id").asText();
    }

    private static final class SseCollector {
        private final StringBuilder text = new StringBuilder();
        private final Thread thread;

        SseCollector(InputStream input) {
            thread = Thread.ofVirtual().start(() -> {
                try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int n;
                    while ((n = reader.read(buffer)) >= 0) {
                        synchronized (text) {
                            text.append(buffer, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                    // 连接关闭
                }
            });
        }

        String text() {
            synchronized (text) {
                return text.toString();
            }
        }

        void join() throws InterruptedException {
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void openAiNonStreamHangThenReply() throws Exception {
        String cookie = login();
        CompletableFuture<HttpResponse<String>> pendingCaller = http.sendAsync(
                callerRequest(openAiBody("IT 非流式", false), "CherryStudio/1.0.0", null).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        PendingJob job = waitPending(1).get(0);
        HttpResponse<String> reply = adminReply(cookie, job.id(), "Java 人工回复");
        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(Json.parse(reply.body()).path("delivered").asBoolean()).isTrue();

        HttpResponse<String> response = pendingCaller.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = Json.parse(response.body());
        assertThat(body.path("object").asText()).isEqualTo("chat.completion");
        assertThat(body.path("model").asText()).isEqualTo("gpt-test");
        assertThat(body.path("choices").get(0).path("message").path("content").asText())
                .isEqualTo("Java 人工回复");
        assertThat(body.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
        assertThat(body.path("usage").path("total_tokens").asInt()).isGreaterThan(0);

        List<ThreadStore.MessageRecord> messages = threads.listMessages(job.threadId());
        assertThat(messages).extracting(ThreadStore.MessageRecord::role)
                .containsExactly("user", "assistant");
        assertThat(messages).extracting(ThreadStore.MessageRecord::source)
                .containsExactly("api", "api");
    }

    @Test
    void openAiStreamHasRoleFramePingTypewriterAndDone() throws Exception {
        String cookie = login();
        HttpRequest request = callerRequest(openAiBody("IT 流式", true), "ChatBox/2.5.0", null).build();

        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = future.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        assertThat(response.headers().firstValue("x-accel-buffering").orElse("")).isEqualTo("no");

        SseCollector collector = new SseCollector(response.body());
        waitUntil(() -> collector.text().contains("\"delta\":{\"role\":\"assistant\",\"content\":\"\"}"), 5000);
        PendingJob job = waitPending(1).get(0);
        Thread.sleep(180); // sse-ping-ms = 80，确保至少一个 ping
        assertThat(collector.text()).contains(": ping");

        HttpResponse<String> reply = adminReply(cookie, job.id(), "流式人工回复内容");
        assertThat(reply.statusCode()).isEqualTo(200);
        collector.join();

        String text = collector.text();
        List<String> deltas = new java.util.ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("\"delta\":\\{\"content\":\"([^\"]*)\"\\}").matcher(text);
        while (matcher.find()) deltas.add(matcher.group(1));
        assertThat(deltas).hasSizeGreaterThan(1);
        assertThat(String.join("", deltas)).isEqualTo("流式人工回复内容");
        assertThat(text).contains("\"finish_reason\":\"stop\"");
        assertThat(text).contains("\"usage\"");
        assertThat(text.trim()).endsWith("data: [DONE]");
    }

    @Test
    void anthropicCountTokensAndNonStream() throws Exception {
        String cookie = login();
        HttpResponse<String> count = call("POST", "/v1/messages/count_tokens",
                Json.stringify(Map.of(
                        "model", "m",
                        "system", "你是助手",
                        "messages", List.of(Map.of("role", "user", "content", "你好世界")))), null,
                "x-api-key", CALLER_KEY);
        assertThat(count.statusCode()).isEqualTo(200);
        assertThat(Json.parse(count.body()).path("input_tokens").asInt()).isGreaterThanOrEqualTo(8);

        CompletableFuture<HttpResponse<String>> pendingCaller = http.sendAsync(
                HttpRequest.newBuilder(URI.create(url("/v1/messages")))
                        .timeout(Duration.ofSeconds(15))
                        .header("content-type", "application/json")
                        .header("x-api-key", CALLER_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(Map.of(
                                "model", "claude-test",
                                "max_tokens", 100,
                                "system", "系统提示",
                                "messages", List.of(Map.of("role", "user", "content", "IT Anthropic"))))))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        PendingJob job = waitPending(1).get(0);
        assertThat(adminReply(cookie, job.id(), "Anthropic 人工回复").statusCode()).isEqualTo(200);
        HttpResponse<String> response = pendingCaller.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = Json.parse(response.body());
        assertThat(body.path("type").asText()).isEqualTo("message");
        assertThat(body.path("content").get(0).path("text").asText()).isEqualTo("Anthropic 人工回复");
        assertThat(body.path("stop_reason").asText()).isEqualTo("end_turn");
    }

    @Test
    void anthropicStreamEvents() throws Exception {
        String cookie = login();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/v1/messages")))
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "application/json")
                .header("x-api-key", CALLER_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(Map.of(
                        "model", "claude-test",
                        "max_tokens", 100,
                        "stream", true,
                        "messages", List.of(Map.of("role", "user", "content", "IT Anthropic 流式"))))))
                .build();
        HttpResponse<InputStream> response =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        SseCollector collector = new SseCollector(response.body());
        waitUntil(() -> collector.text().contains("event: message_start"), 5000);
        PendingJob job = waitPending(1).get(0);
        assertThat(adminReply(cookie, job.id(), "Anthropic 流式回复").statusCode()).isEqualTo(200);
        collector.join();

        String text = collector.text();
        assertThat(text).contains("event: message_start");
        assertThat(text).contains("event: content_block_start");
        assertThat(text).contains("event: content_block_delta");
        assertThat(text).contains("event: content_block_stop");
        assertThat(text).contains("event: message_delta");
        assertThat(text).contains("event: message_stop");
        List<String> deltas = new java.util.ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("\"type\":\"text_delta\",\"text\":\"([^\"]*)\"").matcher(text);
        while (matcher.find()) deltas.add(matcher.group(1));
        assertThat(String.join("", deltas)).isEqualTo("Anthropic 流式回复");
    }

    @Test
    void callerHeaderIsolatesThreads() throws Exception {
        String cookie = login();
        String body = openAiBody("你好", false);
        CompletableFuture<HttpResponse<String>> zhang = http.sendAsync(
                callerRequest(body, "CherryStudio/1.0.0", "%E5%BC%A0%E4%B8%89").build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        waitPending(1);
        CompletableFuture<HttpResponse<String>> li = http.sendAsync(
                callerRequest(body, "CherryStudio/1.0.0", "%E6%9D%8E%E5%9B%9B").build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        List<PendingJob> pending = waitPending(2);
        assertThat(pending).hasSize(2);

        JsonNode list = adminThreads(cookie).path("threads");
        String zhangThread = null;
        String liThread = null;
        for (JsonNode thread : list) {
            if ("张三".equals(thread.path("sourceClient").asText())) zhangThread = thread.path("id").asText();
            if ("李四".equals(thread.path("sourceClient").asText())) liThread = thread.path("id").asText();
        }
        assertThat(zhangThread).isNotNull();
        assertThat(liThread).isNotNull();
        assertThat(zhangThread).isNotEqualTo(liThread);
        assertThat(list.findValuesAsText("sourceIp")).contains("127.0.0.1");

        for (PendingJob job : pending) {
            assertThat(adminReply(cookie, job.id(), "ok").statusCode()).isEqualTo(200);
        }
        assertThat(zhang.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(li.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    @Test
    void timeoutReturnsProtocolError() throws Exception {
        settings.setSetting(SettingsService.DEFAULT_TIMEOUT_MS, "300");
        HttpResponse<String> response = call("POST", "/v1/chat/completions", openAiBody("IT 超时", false), null,
                "authorization", "Bearer " + CALLER_KEY);
        assertThat(response.statusCode()).isEqualTo(504);
        JsonNode body = Json.parse(response.body());
        assertThat(body.path("error").path("code").asText()).isEqualTo("timeout");
        assertThat(body.path("error").path("type").asText()).isEqualTo("server_error");
    }

    @Test
    void deleteThreadFinishesPendingAndCascades() throws Exception {
        String cookie = login();
        CompletableFuture<HttpResponse<String>> caller = http.sendAsync(
                callerRequest(openAiBody("IT 删除", false), "ChatBox/1.0.0", null).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        PendingJob job = waitPending(1).get(0);

        HttpResponse<String> deletion = call("DELETE", "/admin/api/threads/" + job.threadId(), null, cookie);
        assertThat(deletion.statusCode()).isEqualTo(200);
        assertThat(Json.parse(deletion.body()).path("failedJobs").asInt()).isEqualTo(1);

        HttpResponse<String> response = caller.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(threads.listMessages(job.threadId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE thread_id = ?", Integer.class, job.threadId()))
                .isZero();
    }

    @Test
    void batchDeleteThreadsFinishesPendingAndCascades() throws Exception {
        String cookie = login();
        CompletableFuture<HttpResponse<String>> a = http.sendAsync(
                callerRequest(openAiBody("IT 批量删除 A", false), "ChatBox/1.0.0", null).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<String>> b = http.sendAsync(
                callerRequest(openAiBody("IT 批量删除 B", false), "ChatBox/1.0.0", null).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var jobs = waitPending(2);
        String firstId = jobs.get(0).threadId();
        String secondId = jobs.get(1).threadId();

        HttpResponse<String> deletion = call("DELETE", "/admin/api/threads",
                Json.stringify(Map.of("ids", List.of(firstId, secondId))), cookie);
        assertThat(deletion.statusCode()).isEqualTo(200);
        JsonNode result = Json.parse(deletion.body());
        assertThat(result.path("deleted").asInt()).isEqualTo(2);
        assertThat(result.path("failedJobs").asInt()).isEqualTo(2);

        assertThat(a.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(504);
        assertThat(b.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(504);
        assertThat(threads.getThread(firstId)).isNull();
        assertThat(threads.getThread(secondId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE thread_id = ?",
                Integer.class, firstId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE thread_id = ?",
                Integer.class, secondId)).isZero();
    }

    @Test
    void adminRequestMonitorListsParameters() throws Exception {
        String cookie = login();
        String marker = "IT-MON-9f3a";
        CompletableFuture<HttpResponse<String>> caller = http.sendAsync(
                callerRequest(openAiBody("请处理 " + marker, false), "ChatBox/1.0.0", null).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        PendingJob job = waitPending(1).get(0);

        HttpResponse<String> list = call("GET",
                "/admin/api/requests?limit=10&offset=0&status=pending&q=" + marker, null, cookie);
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode page = Json.parse(list.body());
        assertThat(page.path("total").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode item = null;
        for (JsonNode node : page.path("requests")) {
            if (job.id().equals(node.path("id").asText())) item = node;
        }
        assertThat(item).isNotNull();
        assertThat(item.path("requestPreview").asText()).contains(marker);
        assertThat(item.path("protocol").asText()).isEqualTo("openai");
        assertThat(item.path("sourceIp").asText()).isNotEmpty();

        HttpResponse<String> detail = call("GET", "/admin/api/requests/" + job.id(), null, cookie);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode detailBody = Json.parse(detail.body());
        assertThat(detailBody.path("requestJson").asText()).contains(marker);
        assertThat(detailBody.path("threadId").asText()).isEqualTo(job.threadId());
        assertThat(detailBody.path("replyContent").isNull()).isTrue();

        assertThat(adminReply(cookie, job.id(), "监控回复").statusCode()).isEqualTo(200);
        assertThat(caller.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);

        HttpResponse<String> replied = call("GET",
                "/admin/api/requests?limit=10&offset=0&status=replied&q=" + marker, null, cookie);
        JsonNode repliedPage = Json.parse(replied.body());
        assertThat(repliedPage.path("total").asInt()).isEqualTo(1);
        assertThat(repliedPage.path("requests").get(0).path("status").asText()).isEqualTo("replied");
    }

    @Test
    void corsIsOnlyEnabledForGatewayAndAdminApis() throws Exception {
        HttpResponse<String> preflight = call("OPTIONS", "/v1/chat/completions", null, null,
                "origin", "http://example.com",
                "access-control-request-method", "POST",
                "access-control-request-headers", "authorization,content-type");
        assertThat(preflight.statusCode()).isLessThan(300);
        assertThat(preflight.headers().firstValue("access-control-allow-origin").orElse("")).isEqualTo("*");

        HttpResponse<String> dounaiPreflight = call("OPTIONS", "/api/chat", null, null,
                "origin", "http://example.com",
                "access-control-request-method", "POST");
        assertThat(dounaiPreflight.headers().firstValue("access-control-allow-origin")).isEmpty();
    }

    @Test
    void compatEndpoints() throws Exception {
        for (String path : List.of("/healthz", "/api/hello", "/v1/api/hello")) {
            assertThat(call("GET", path, null, null).statusCode()).isEqualTo(200);
        }
        assertThat(call("POST", "/api/event_logging/batch", "{}", null).statusCode()).isEqualTo(200);
        assertThat(call("PUT", "/api/event_logging", "{}", null).statusCode()).isEqualTo(200);
    }

    @Test
    void adminAuthAndSettings() throws Exception {
        assertThat(call("GET", "/admin/api/threads", null, null).statusCode()).isEqualTo(401);
        String cookie = login();
        assertThat(call("GET", "/admin/api/me", null, cookie).statusCode()).isEqualTo(200);
        HttpResponse<String> settingsResponse = call("GET", "/admin/api/settings", null, cookie);
        assertThat(settingsResponse.statusCode()).isEqualTo(200);
        JsonNode settingsBody = Json.parse(settingsResponse.body());
        assertThat(settingsBody.path("callerKey").asText()).isEqualTo(CALLER_KEY);
        assertThat(settingsBody.path("modelName").asText()).isEqualTo("chatshy");
        assertThat(call("GET", "/v1/models", null, null, "authorization", "Bearer " + CALLER_KEY).statusCode())
                .isEqualTo(200);
        assertThat(call("GET", "/v1/models", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void adminEventStreamReceivesLifecycleEvents() throws Exception {
        String cookie = login();
        HttpRequest eventsRequest = HttpRequest.newBuilder(URI.create(url("/admin/api/events")))
                .timeout(Duration.ofSeconds(15))
                .header("cookie", cookie)
                .GET()
                .build();
        HttpResponse<InputStream> events =
                http.sendAsync(eventsRequest, HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
        assertThat(events.statusCode()).isEqualTo(200);
        SseCollector collector = new SseCollector(events.body());
        waitUntil(() -> collector.text().contains("\"hello\""), 5000);

        CompletableFuture<HttpResponse<String>> caller = http.sendAsync(
                callerRequest(openAiBody("IT 事件", false), "ChatBox/1.0.0", null).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        PendingJob job = waitPending(1).get(0);
        waitUntil(() -> collector.text().contains("\"job.created\""), 5000);
        assertThat(adminReply(cookie, job.id(), "事件回复").statusCode()).isEqualTo(200);
        assertThat(caller.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        waitUntil(() -> collector.text().contains("\"job.replied\""), 5000);
        events.body().close();
        collector.join();
    }

    @Test
    void manualForwardUsesProviderAndMarksVia() throws Exception {
        try (MockUpstream upstream = new MockUpstream()) {
            upstream.setHandler((request, out) -> {
                JsonNode body = Json.parse(request.body());
                String lastUser = "";
                for (JsonNode message : body.path("messages")) {
                    if ("user".equals(message.path("role").asText())) {
                        lastUser = message.path("content").asText();
                    }
                }
                MockUpstream.writeJson(out, 200, Json.stringify(Map.of("choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", "AI: " + lastUser))))));
            });

            String cookie = login();
            String providerId = createProvider(cookie, upstream.base() + "/v1");
            CompletableFuture<HttpResponse<String>> caller = http.sendAsync(
                    callerRequest(Json.stringify(Map.of(
                            "model", "m",
                            "messages", List.of(
                                    Map.of("role", "system", "content", "你是简洁助手"),
                                    Map.of("role", "user", "content", "手动转发")))), "CherryStudio/1.0.0", null).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            PendingJob job = waitPending(1).get(0);

            HttpResponse<String> forward = call("POST", "/admin/api/jobs/" + job.id() + "/forward",
                    Json.stringify(Map.of("providerId", providerId)), cookie);
            assertThat(forward.statusCode()).isEqualTo(200);
            JsonNode forwardBody = Json.parse(forward.body());
            assertThat(forwardBody.path("content").asText()).isEqualTo("AI: 手动转发");
            assertThat(forwardBody.path("providerName").asText()).startsWith("测试厂商-");

            HttpResponse<String> response = caller.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(Json.parse(response.body()).path("choices").get(0).path("message").path("content").asText())
                    .isEqualTo("AI: 手动转发");

            List<MockUpstream.Req> requests = upstream.requests();
            assertThat(requests).hasSize(1);
            assertThat(requests.get(0).path()).isEqualTo("/v1/chat/completions");
            assertThat(requests.get(0).headers().get("authorization")).isEqualTo("Bearer test-upstream-key");
            JsonNode sent = Json.parse(requests.get(0).body());
            assertThat(sent.path("model").asText()).isEqualTo("upstream-model");
            assertThat(sent.path("stream").asBoolean()).isFalse();
            assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(sent.path("messages").get(0).path("content").asText()).isEqualTo("你是简洁助手");

            JsonNode detail = Json.parse(call("GET", "/admin/api/threads/" + job.threadId(), null, cookie).body());
            JsonNode assistant = null;
            JsonNode user = null;
            for (JsonNode message : detail.path("messages")) {
                if ("assistant".equals(message.path("role").asText())) assistant = message;
                if ("user".equals(message.path("role").asText())) user = message;
            }
            assertThat(assistant).isNotNull();
            assertThat(assistant.path("content").asText()).isEqualTo("AI: 手动转发");
            assertThat(assistant.path("via").asText()).startsWith("测试厂商-");
            assertThat(user.path("via").asText()).isEmpty();
            assertThat(detail.path("pendingJobs")).isEmpty();
        }
    }

    @Test
    void autoForwardStreamIsRealtime() throws Exception {
        try (MockUpstream upstream = new MockUpstream()) {
            upstream.setHandler((request, out) -> {
                MockUpstream.writeSseHead(out);
                MockUpstream.writeSseData(out, MockUpstream.contentChunk("第一段"));
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                MockUpstream.writeSseData(out, MockUpstream.contentChunk("，第二段"));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            });

            String cookie = login();
            String providerId = createProvider(cookie, upstream.base() + "/v1");
            HttpResponse<String> patch = call("PATCH", "/admin/api/settings", Json.stringify(Map.of(
                    "autoForwardEnabled", true,
                    "autoForwardProviderId", providerId)), cookie);
            assertThat(patch.statusCode()).isEqualTo(200);

            HttpRequest request = callerRequest(openAiBody("流式转发", true), "CherryStudio/1.0.0", null).build();
            HttpResponse<InputStream> response =
                    http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            SseCollector collector = new SseCollector(response.body());
            waitUntil(() -> collector.text().contains("第一段"), 5000);
            assertThat(collector.text()).doesNotContain("第二段");
            collector.join();

            String text = collector.text();
            List<String> deltas = new java.util.ArrayList<>();
            var matcher = java.util.regex.Pattern.compile("\"delta\":\\{\"content\":\"([^\"]*)\"\\}").matcher(text);
            while (matcher.find()) deltas.add(matcher.group(1));
            assertThat(String.join("", deltas)).isEqualTo("第一段，第二段");
            assertThat(text).contains("\"finish_reason\":\"stop\"");
            assertThat(text.trim()).endsWith("data: [DONE]");

            assertThat(upstream.requests()).hasSize(1);
            assertThat(Json.parse(upstream.requests().get(0).body()).path("stream").asBoolean()).isTrue();
        }
    }

    @Test
    void forwardFailureKeepsJobPendingForHuman() throws Exception {
        try (MockUpstream upstream = new MockUpstream()) {
            upstream.setHandler((request, out) ->
                    MockUpstream.writeJson(out, 500, "{\"error\":{\"message\":\"上游炸了\"}}"));

            String cookie = login();
            String providerId = createProvider(cookie, upstream.base() + "/v1");
            CompletableFuture<HttpResponse<String>> caller = http.sendAsync(
                    callerRequest(openAiBody("会失败", false), "ChatBox/1.0.0", null).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            PendingJob job = waitPending(1).get(0);

            HttpResponse<String> forward = call("POST", "/admin/api/jobs/" + job.id() + "/forward",
                    Json.stringify(Map.of("providerId", providerId)), cookie);
            assertThat(forward.statusCode()).isEqualTo(502);
            JsonNode forwardBody = Json.parse(forward.body());
            assertThat(forwardBody.path("error").asText()).isEqualTo("upstream_error");
            assertThat(forwardBody.path("message").asText()).contains("上游炸了");
            assertThat(queue.listPending()).hasSize(1);

            JsonNode detail = Json.parse(call("GET", "/admin/api/threads/" + job.threadId(), null, cookie).body());
            assertThat(detail.path("pendingJobs").get(0).path("forwardError").asText()).contains("上游炸了");

            assertThat(adminReply(cookie, job.id(), "人工接管").statusCode()).isEqualTo(200);
            HttpResponse<String> response = caller.get(10, TimeUnit.SECONDS);
            assertThat(Json.parse(response.body()).path("choices").get(0).path("message").path("content").asText())
                    .isEqualTo("人工接管");
        }
    }
}
