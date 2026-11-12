package io.github.cn3shy.chatshy.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.Forwarder;
import io.github.cn3shy.chatshy.core.HubEvent;
import io.github.cn3shy.chatshy.core.HttpUtil;
import io.github.cn3shy.chatshy.core.JobQueue;
import io.github.cn3shy.chatshy.core.Json;
import io.github.cn3shy.chatshy.core.OperatorHub;
import io.github.cn3shy.chatshy.core.PendingJob;
import io.github.cn3shy.chatshy.core.ProviderClient;
import io.github.cn3shy.chatshy.core.ProviderStore;
import io.github.cn3shy.chatshy.core.ProviderStore.Provider;
import io.github.cn3shy.chatshy.core.ProviderStore.ProviderPatch;
import io.github.cn3shy.chatshy.core.ProviderStore.ProviderValidationException;
import io.github.cn3shy.chatshy.core.SettingsService;
import io.github.cn3shy.chatshy.core.ThreadStore;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ChatMessage;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.Prompt;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
public class AdminController {
    private record ThreadListRow(String id, String title, String protocol, String model,
                                 long createdAt, long updatedAt, String sourceIp, String sourceUa,
                                 String sourceClient, String sourceFingerprint, String lastContent,
                                 int pendingCount, String pendingJobId, Long pendingDeadline) {
    }

    private record JobBrief(String id, String status, String via) {
    }

    private record PendingRow(String id, String protocol, String mode, long deadlineAt,
                              String forwardError, Long forwardErrorAt) {
    }

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final ThreadStore threads;
    private final JobQueue queue;
    private final OperatorHub hub;
    private final ProviderStore providers;
    private final ProviderClient providerClient;
    private final Forwarder forwarder;
    private final SessionStore sessions;

    public AdminController(JdbcTemplate jdbc, SettingsService settings, ThreadStore threads, JobQueue queue,
                           OperatorHub hub, ProviderStore providers, ProviderClient providerClient,
                           Forwarder forwarder, SessionStore sessions) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.threads = threads;
        this.queue = queue;
        this.hub = hub;
        this.providers = providers;
        this.providerClient = providerClient;
        this.forwarder = forwarder;
        this.sessions = sessions;
    }

    // ---- 登录 ----

    @PostMapping("/admin/api/login")
    public ResponseEntity<?> login(@RequestBody(required = false) byte[] rawBody,
                                   HttpServletRequest request, HttpServletResponse response) {
        String ip = HttpUtil.clientIp(request);
        if (sessions.isLocked(ip)) return ResponseEntity.status(429).body(Map.of("error", "too_many_attempts"));

        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        String password = body != null && body.path("password").isTextual() ? body.path("password").asText() : "";
        if (!settings.verifyAdminPassword(password)) {
            sessions.registerFailure(ip);
            return ResponseEntity.status(401).body(Map.of("error", "invalid_password"));
        }
        sessions.resetFailures(ip);
        String token = sessions.create();
        ResponseCookie cookie = ResponseCookie.from(SessionStore.SESSION_COOKIE, token)
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofDays(7))
                .secure(HttpUtil.isHttps(request))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/admin/api/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.destroy(AdminAuthInterceptor.sessionToken(request));
        ResponseCookie cookie = ResponseCookie.from(SessionStore.SESSION_COOKIE, "")
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/admin/api/me")
    public ResponseEntity<?> me() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ---- 实时事件（SSE） ----

    @GetMapping("/admin/api/events")
    public void events(HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        OutputStream output = response.getOutputStream();
        output.write("data: {\"type\":\"hello\"}\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();

        CountDownLatch closed = new CountDownLatch(1);
        Object lock = new Object();
        OperatorHub.HubClient client = frame -> {
            synchronized (lock) {
                try {
                    output.write(frame.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (IOException e) {
                    closed.countDown();
                    throw e;
                }
            }
        };
        hub.addClient(client);
        try {
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            hub.removeClient(client);
            try {
                output.close();
            } catch (IOException ignored) {
                // 连接已断开
            }
        }
    }

    // ---- 会话列表 / 详情 ----

    @GetMapping("/admin/api/threads")
    public ResponseEntity<?> listThreads() {
        List<ThreadListRow> rows = jdbc.query("""
                SELECT t.id, t.title, t.protocol, t.model, t.created_at, t.updated_at,
                       t.source_ip, t.source_ua, t.source_client, t.source_fingerprint,
                       (SELECT content FROM messages m WHERE m.thread_id = t.id ORDER BY m.created_at DESC, m.rowid DESC LIMIT 1) AS last_content,
                       (SELECT COUNT(*) FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending') AS pending_count,
                       (SELECT j.id FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending' ORDER BY j.created_at DESC LIMIT 1) AS pending_job_id,
                       (SELECT j.deadline_at FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending' ORDER BY j.created_at DESC LIMIT 1) AS pending_deadline
                FROM threads t
                ORDER BY (pending_count > 0) DESC, pending_deadline ASC, t.updated_at DESC
                LIMIT 200
                """, (rs, i) -> new ThreadListRow(
                rs.getString("id"), rs.getString("title"), rs.getString("protocol"), rs.getString("model"),
                rs.getLong("created_at"), rs.getLong("updated_at"), rs.getString("source_ip"),
                rs.getString("source_ua"), rs.getString("source_client"), rs.getString("source_fingerprint"),
                rs.getString("last_content"), rs.getInt("pending_count"), rs.getString("pending_job_id"),
                (Long) rs.getObject("pending_deadline")));

        List<Map<String, Object>> list = new ArrayList<>();
        for (ThreadListRow row : rows) {
            Map<String, Object> item = Json.obj();
            item.put("id", row.id());
            item.put("title", row.title());
            item.put("protocol", row.protocol());
            item.put("model", row.model());
            item.put("sourceClient", row.sourceClient());
            item.put("sourceIp", row.sourceIp());
            item.put("sourceUa", row.sourceUa());
            item.put("createdAt", row.createdAt());
            item.put("updatedAt", row.updatedAt());
            item.put("lastMessage", row.lastContent() == null ? "" : row.lastContent());
            item.put("pendingCount", row.pendingCount());
            item.put("pendingJobId", row.pendingJobId());
            item.put("pendingDeadlineAt", row.pendingDeadline());
            list.add(item);
        }
        return ResponseEntity.ok(Map.of("threads", list));
    }

    @GetMapping("/admin/api/threads/{id}")
    public ResponseEntity<?> threadDetail(@PathVariable String id) {
        ThreadStore.ThreadRecord thread = threads.getThread(id);
        if (thread == null) return ResponseEntity.status(404).body(Map.of("error", "not_found"));

        Map<String, JobBrief> jobById = new HashMap<>();
        for (JobBrief job : jdbc.query("SELECT id, status, via FROM jobs WHERE thread_id = ?",
                (rs, i) -> new JobBrief(rs.getString("id"), rs.getString("status"), rs.getString("via")), id)) {
            jobById.put(job.id(), job);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        for (ThreadStore.MessageRecord message : threads.listMessages(id)) {
            JobBrief job = message.jobId() == null ? null : jobById.get(message.jobId());
            Map<String, Object> item = Json.obj();
            item.put("id", message.id());
            item.put("role", message.role());
            item.put("content", message.content());
            item.put("source", message.source());
            item.put("jobId", message.jobId());
            item.put("createdAt", message.createdAt());
            item.put("delivered", message.jobId() == null || (job != null && "replied".equals(job.status())));
            item.put("via", "assistant".equals(message.role()) && job != null && job.via() != null
                    ? job.via() : "");
            messages.add(item);
        }

        List<PendingRow> pendingRows = jdbc.query("""
                SELECT id, protocol, mode, deadline_at, forward_error, forward_error_at
                FROM jobs WHERE thread_id = ? AND status = 'pending' ORDER BY created_at ASC
                """, (rs, i) -> new PendingRow(rs.getString("id"), rs.getString("protocol"),
                rs.getString("mode"), rs.getLong("deadline_at"), rs.getString("forward_error"),
                (Long) rs.getObject("forward_error_at")), id);

        List<Map<String, Object>> pendingJobs = new ArrayList<>();
        for (PendingRow row : pendingRows) {
            Map<String, Object> item = Json.obj();
            item.put("id", row.id());
            item.put("protocol", row.protocol());
            item.put("mode", row.mode());
            item.put("deadlineAt", row.deadlineAt());
            item.put("forwardError", row.forwardError());
            item.put("forwardErrorAt", row.forwardErrorAt());
            pendingJobs.add(item);
        }

        Map<String, Object> threadMap = Json.obj();
        threadMap.put("id", thread.id());
        threadMap.put("title", thread.title());
        threadMap.put("protocol", thread.protocol());
        threadMap.put("model", thread.model());
        threadMap.put("sourceClient", thread.sourceClient());
        threadMap.put("sourceIp", thread.sourceIp());
        threadMap.put("sourceUa", thread.sourceUa());
        threadMap.put("createdAt", thread.createdAt());
        threadMap.put("updatedAt", thread.updatedAt());

        Map<String, Object> body = Json.obj();
        body.put("thread", threadMap);
        body.put("messages", messages);
        body.put("pendingJobs", pendingJobs);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/admin/api/threads/{id}")
    public ResponseEntity<?> deleteThread(@PathVariable String id) {
        if (threads.getThread(id) == null) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        int failed = queue.failForThread(id);
        threads.deleteThread(id);
        hub.broadcast(HubEvent.threadDeleted(id));
        return ResponseEntity.ok(Map.of("ok", true, "failedJobs", failed));
    }

    // ---- 回复 / 忽略 / 转发 ----

    @PostMapping("/admin/api/jobs/{id}/reply")
    public ResponseEntity<?> reply(@PathVariable String id, @RequestBody(required = false) byte[] rawBody) {
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        String content = body != null && body.path("content").isTextual() ? body.path("content").asText() : "";
        if (content.trim().isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "empty_content"));

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT thread_id, status FROM jobs WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        String threadId = String.valueOf(rows.get(0).get("thread_id"));
        String status = String.valueOf(rows.get(0).get("status"));

        PendingJob liveJob = queue.get(id);
        if (liveJob != null && liveJob.hasStreamed()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "forward_in_progress",
                    "message", "AI 正在流式回复，请等它结束或先忽略这个请求"));
        }

        if (queue.reply(id, content, null)) {
            return ResponseEntity.ok(Map.of("ok", true, "delivered", true));
        }
        if ("replied".equals(status)) {
            return ResponseEntity.status(409).body(Map.of("error", "already_replied"));
        }
        threads.appendMessage(threadId, "assistant", content, "console", id);
        return ResponseEntity.ok(Map.of("ok", true, "delivered", false));
    }

    @PostMapping("/admin/api/jobs/{id}/ignore")
    public ResponseEntity<?> ignore(@PathVariable String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM jobs WHERE id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        queue.ignore(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/admin/api/jobs/{id}/forward")
    public ResponseEntity<?> forward(@PathVariable String id, @RequestBody(required = false) byte[] rawBody) {
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        String providerId = body != null && body.path("providerId").isTextual()
                ? body.path("providerId").asText().trim() : "";
        if (providerId.isEmpty()) return ResponseEntity.status(400).body(Map.of("error", "invalid_provider"));

        PendingJob job = queue.get(id);
        if (job == null) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT status FROM jobs WHERE id = ?", id);
            if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
            return ResponseEntity.status(409).body(Map.of("error", "job_ended", "status",
                    String.valueOf(rows.get(0).get("status"))));
        }

        Forwarder.ForwardResult result = forwarder.forwardPendingJob(job, providerId);
        return switch (result.kind()) {
            case REPLIED -> ResponseEntity.ok(repliedBody(result));
            case IN_PROGRESS -> ResponseEntity.status(409).body(Map.of("error", "forward_in_progress"));
            case PROVIDER_NOT_FOUND -> ResponseEntity.status(404).body(Map.of("error", "provider_not_found"));
            case BAD_REQUEST -> ResponseEntity.status(400).body(Map.of(
                    "error", "bad_request", "message", result.message() == null ? "" : result.message()));
            case JOB_ENDED -> ResponseEntity.status(409).body(Map.of(
                    "error", "job_ended", "status", result.status() == null ? "" : result.status()));
            case UPSTREAM_ERROR -> ResponseEntity.status(502).body(Map.of(
                    "error", "upstream_error",
                    "message", result.message() == null ? "" : result.message(),
                    "provider", result.providerName() == null ? "" : result.providerName()));
        };
    }

    private static Map<String, Object> repliedBody(Forwarder.ForwardResult result) {
        Map<String, Object> body = Json.obj();
        body.put("ok", true);
        body.put("delivered", true);
        body.put("content", result.content());
        body.put("providerName", result.providerName());
        return body;
    }

    // ---- AI 厂商配置 ----

    @GetMapping("/admin/api/providers")
    public ResponseEntity<?> listProviders() {
        List<ProviderStore.PublicProvider> list = providers.listProviders().stream()
                .map(ProviderStore::toPublic).toList();
        return ResponseEntity.ok(Map.of("providers", list));
    }

    @PostMapping("/admin/api/providers")
    public ResponseEntity<?> createProvider(@RequestBody(required = false) byte[] rawBody) {
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        try {
            Provider provider = providers.createProvider(new ProviderStore.ProviderInput(
                    text(body, "name"), text(body, "baseUrl"), text(body, "apiKey"), text(body, "model")));
            hub.broadcast(HubEvent.providersUpdated());
            return ResponseEntity.ok(Map.of("provider", ProviderStore.toPublic(provider)));
        } catch (ProviderValidationException e) {
            return providerErrorReply(e);
        }
    }

    @PatchMapping("/admin/api/providers/{id}")
    public ResponseEntity<?> updateProvider(@PathVariable String id, @RequestBody(required = false) byte[] rawBody) {
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        if (body == null) body = Json.MAPPER.createObjectNode();
        String[] keys = {"name", "baseUrl", "apiKey", "model"};
        for (String key : keys) {
            JsonNode value = body.get(key);
            if (value != null && !value.isTextual()) {
                return ResponseEntity.status(400).body(Map.of("error", "invalid_" + key));
            }
        }
        ProviderPatch patch = new ProviderPatch(
                body.has("name") ? body.get("name").asText() : null,
                body.has("baseUrl") ? body.get("baseUrl").asText() : null,
                body.has("apiKey") ? body.get("apiKey").asText() : null,
                body.has("model") ? body.get("model").asText() : null);
        try {
            Provider provider = providers.updateProvider(id, patch);
            if (provider == null) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
            hub.broadcast(HubEvent.providersUpdated());
            return ResponseEntity.ok(Map.of("provider", ProviderStore.toPublic(provider)));
        } catch (ProviderValidationException e) {
            return providerErrorReply(e);
        }
    }

    @DeleteMapping("/admin/api/providers/{id}")
    public ResponseEntity<?> deleteProvider(@PathVariable String id) {
        if (providers.getProvider(id) == null) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        if (settings.getAutoForwardProviderId().equals(id)) {
            if (settings.isAutoForwardEnabled()) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "provider_in_use",
                        "message", "该厂商正被自动转发使用，请先关闭自动转发或切换厂商"));
            }
            settings.setAutoForwardProviderId("");
            hub.broadcast(HubEvent.settingsUpdated());
        }
        providers.deleteProvider(id);
        hub.broadcast(HubEvent.providersUpdated());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/admin/api/providers/{id}/test")
    public ResponseEntity<?> testProvider(@PathVariable String id) {
        Provider provider = providers.getProvider(id);
        if (provider == null) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        long startedAt = System.currentTimeMillis();
        try {
            String content = providerClient.callProvider(provider,
                    new Prompt(provider.model(), null, List.of(new ChatMessage("user", "只回复 OK"))),
                    20_000, new ProviderClient.AbortHandle());
            int previewLen = Math.min(200, content.length());
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "latencyMs", System.currentTimeMillis() - startedAt,
                    "preview", content.substring(0, previewLen)));
        } catch (ProviderClient.ProviderError e) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", e.code().name().toLowerCase(),
                    "message", e.getMessage() == null ? "" : e.getMessage(),
                    "provider", provider.name()));
        }
    }

    private static ResponseEntity<?> providerErrorReply(ProviderValidationException e) {
        int status = "provider_exists".equals(e.code()) ? 409 : 400;
        return ResponseEntity.status(status).body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    // ---- 全局设置 ----

    @GetMapping("/admin/api/settings")
    public ResponseEntity<?> getSettings() {
        return ResponseEntity.ok(settingsPayload());
    }

    @PostMapping("/admin/api/caller-key")
    public ResponseEntity<?> regenerateCallerKey() {
        return ResponseEntity.ok(Map.of("callerKey", settings.regenerateCallerKey()));
    }

    @PatchMapping("/admin/api/settings")
    public ResponseEntity<?> patchSettings(@RequestBody(required = false) byte[] rawBody) {
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        if (body == null) body = Json.MAPPER.createObjectNode();

        String newTimeout = null;
        if (body.has("defaultTimeoutMs")) {
            JsonNode node = body.get("defaultTimeoutMs");
            if (node == null || !node.isNumber() || !Double.isFinite(node.asDouble()) || node.asDouble() <= 0) {
                return ResponseEntity.status(400).body(Map.of("error", "invalid_timeout"));
            }
            newTimeout = String.valueOf((long) Math.floor(node.asDouble()));
        }

        String newModel = null;
        if (body.has("modelName")) {
            JsonNode node = body.get("modelName");
            if (node == null || !node.isTextual() || node.asText().trim().isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("error", "invalid_model_name"));
            }
            newModel = node.asText().trim();
        }

        String newPassword = null;
        if (body.has("adminPassword")) {
            JsonNode node = body.get("adminPassword");
            if (node == null || !node.isTextual() || node.asText().length() < 6) {
                return ResponseEntity.status(400).body(Map.of("error", "password_too_short"));
            }
            String current = body.path("currentPassword").isTextual() ? body.path("currentPassword").asText() : "";
            if (!settings.verifyAdminPassword(current)) {
                return ResponseEntity.status(403).body(Map.of("error", "wrong_current_password"));
            }
            newPassword = node.asText();
        }

        Boolean newAutoEnabled = null;
        if (body.has("autoForwardEnabled")) {
            JsonNode node = body.get("autoForwardEnabled");
            if (node == null || !node.isBoolean()) {
                return ResponseEntity.status(400).body(Map.of(
                        "error", "invalid_auto_forward", "message", "自动转发开关必须是布尔值"));
            }
            newAutoEnabled = node.asBoolean();
        }

        String newAutoProvider = null;
        if (body.has("autoForwardProviderId")) {
            JsonNode node = body.get("autoForwardProviderId");
            if (node == null || !node.isTextual()) {
                return ResponseEntity.status(400).body(Map.of(
                        "error", "invalid_auto_forward", "message", "厂商 id 必须是字符串"));
            }
            newAutoProvider = node.asText().trim();
        }

        boolean effectiveEnabled = newAutoEnabled != null ? newAutoEnabled : settings.isAutoForwardEnabled();
        String effectiveProvider = newAutoProvider != null ? newAutoProvider : settings.getAutoForwardProviderId();
        if (effectiveEnabled) {
            if (effectiveProvider.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of(
                        "error", "invalid_auto_forward", "message", "开启自动转发前请先选择厂商"));
            }
            if (providers.getProvider(effectiveProvider) == null) {
                return ResponseEntity.status(400).body(Map.of(
                        "error", "invalid_auto_forward", "message", "所选厂商不存在"));
            }
        }

        if (newTimeout != null) settings.setSetting(SettingsService.DEFAULT_TIMEOUT_MS, newTimeout);
        if (newModel != null) settings.setSetting(SettingsService.MODEL_NAME, newModel);
        if (newPassword != null) settings.setAdminPassword(newPassword);
        if (newAutoEnabled != null) settings.setAutoForwardEnabled(newAutoEnabled);
        if (newAutoProvider != null) settings.setAutoForwardProviderId(newAutoProvider);

        hub.broadcast(HubEvent.settingsUpdated());
        Map<String, Object> result = settingsPayload();
        result.put("ok", true);
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> settingsPayload() {
        Map<String, Object> payload = Json.obj();
        payload.put("defaultTimeoutMs", settings.getDefaultTimeoutMs());
        payload.put("modelName", settings.getModelName());
        payload.put("callerKey", settings.getCallerKey() == null ? "" : settings.getCallerKey());
        payload.put("autoForwardEnabled", settings.isAutoForwardEnabled());
        payload.put("autoForwardProviderId", settings.getAutoForwardProviderId());
        return payload;
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.path(field).isTextual() ? body.path(field).asText() : "";
    }
}
