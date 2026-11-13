package io.github.cn3shy.chatshy.web;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.Forwarder;
import io.github.cn3shy.chatshy.core.HubEvent;
import io.github.cn3shy.chatshy.core.HttpUtil;
import io.github.cn3shy.chatshy.core.JobQueue;
import io.github.cn3shy.chatshy.core.Json;
import io.github.cn3shy.chatshy.core.ModelPriceStore;
import io.github.cn3shy.chatshy.core.OperatorHub;
import io.github.cn3shy.chatshy.core.PendingJob;
import io.github.cn3shy.chatshy.core.ProviderClient;
import io.github.cn3shy.chatshy.core.ProviderStore;
import io.github.cn3shy.chatshy.core.ProviderStore.Provider;
import io.github.cn3shy.chatshy.core.ProviderStore.ProviderPatch;
import io.github.cn3shy.chatshy.core.ProviderStore.ProviderValidationException;
import io.github.cn3shy.chatshy.core.SettingsService;
import io.github.cn3shy.chatshy.core.ThreadStore;
import io.github.cn3shy.chatshy.protocol.AnthropicProtocol;
import io.github.cn3shy.chatshy.protocol.OpenAiProtocol;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ChatMessage;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.Prompt;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
public class AdminController {
    private record ThreadListRow(String id, String title, String protocol, String model,
                                 long createdAt, long updatedAt, String sourceIp, String sourceUa,
                                 String sourceClient, String sourceFingerprint, String lastContent,
                                 boolean blacklisted, int pendingCount, String pendingJobId,
                                 Long pendingDeadline) {
    }

    private record JobBrief(String id, String status, String via) {
    }

    private record PendingRow(String id, String protocol, String mode, long deadlineAt,
                              String forwardError, Long forwardErrorAt) {
    }

    private record RequestRow(String id, String threadId, String threadTitle, String protocol, String mode,
                              String status, String model, long createdAt, Long repliedAt, String via,
                              String providerName, String sourceIp, String sourceUa, String sourceClient,
                              String requestPreview, String replyPreview, String forwardError) {
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
    private final ModelPriceStore modelPrices;

    public AdminController(JdbcTemplate jdbc, SettingsService settings, ThreadStore threads, JobQueue queue,
                           OperatorHub hub, ProviderStore providers, ProviderClient providerClient,
                           Forwarder forwarder, SessionStore sessions, ModelPriceStore modelPrices) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.threads = threads;
        this.queue = queue;
        this.hub = hub;
        this.providers = providers;
        this.providerClient = providerClient;
        this.forwarder = forwarder;
        this.sessions = sessions;
        this.modelPrices = modelPrices;
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
                SELECT * FROM (
                    SELECT t.id, t.title, t.protocol, t.model, t.created_at, t.updated_at,
                           t.source_ip, t.source_ua, t.source_client, t.source_fingerprint,
                           (SELECT content FROM messages m WHERE m.thread_id = t.id ORDER BY m.created_at DESC, m.seq DESC LIMIT 1) AS last_content,
                           t.blacklisted,
                           (SELECT COUNT(*) FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending') AS pending_count,
                           (SELECT j.id FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending' ORDER BY j.created_at DESC LIMIT 1) AS pending_job_id,
                           (SELECT j.deadline_at FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending' ORDER BY j.created_at DESC LIMIT 1) AS pending_deadline
                    FROM threads t
                ) s
                ORDER BY (s.pending_count > 0) DESC, s.pending_deadline ASC, s.updated_at DESC
                LIMIT 200
                """, (rs, i) -> new ThreadListRow(
                rs.getString("id"), rs.getString("title"), rs.getString("protocol"), rs.getString("model"),
                rs.getLong("created_at"), rs.getLong("updated_at"), rs.getString("source_ip"),
                rs.getString("source_ua"), rs.getString("source_client"), rs.getString("source_fingerprint"),
                rs.getString("last_content"), rs.getInt("blacklisted") != 0, rs.getInt("pending_count"),
                rs.getString("pending_job_id"), (Long) rs.getObject("pending_deadline")));

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
            item.put("blacklisted", row.blacklisted());
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
        threadMap.put("blacklisted", thread.blacklisted());

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

    /** 批量删除会话；单次最多 500 条，不存在或重复的 id 幂等忽略。 */
    @DeleteMapping("/admin/api/threads")
    public ResponseEntity<?> deleteThreads(@RequestBody(required = false) byte[] rawBody) {
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        JsonNode idsNode = body == null ? null : body.get("ids");
        if (idsNode == null || !idsNode.isArray() || idsNode.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "empty_ids"));
        }
        if (idsNode.size() > 500) {
            return ResponseEntity.status(400).body(Map.of(
                    "error", "too_many_ids", "message", "单次最多删除 500 条会话"));
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : idsNode) {
            if (node.isTextual() && !node.asText().isBlank()) ids.add(node.asText());
        }
        if (ids.isEmpty()) {
            return ResponseEntity.status(400).body(Map.of("error", "empty_ids"));
        }

        int deleted = 0;
        int failedJobs = 0;
        for (String id : ids) {
            if (threads.getThread(id) == null) continue;
            failedJobs += queue.failForThread(id);
            if (threads.deleteThread(id)) {
                deleted++;
                hub.broadcast(HubEvent.threadDeleted(id));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "deleted", deleted, "failedJobs", failedJobs));
    }

    /** 拉黑 / 解除拉黑：被拉黑的会话在网关入口直接收到 403，不再产生待回复请求。 */
    @PostMapping("/admin/api/threads/{id}/blacklist")
    public ResponseEntity<?> blacklistThread(@PathVariable String id,
                                             @RequestBody(required = false) byte[] rawBody) {
        if (threads.getThread(id) == null) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        boolean blacklisted = body != null && body.path("blacklisted").asBoolean(false);
        threads.setBlacklisted(id, blacklisted);
        return ResponseEntity.ok(Map.of("ok", true, "blacklisted", blacklisted));
    }

    // ---- 请求监控 ----

    @GetMapping("/admin/api/requests")
    public ResponseEntity<?> listRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND j.status = ?");
            args.add(status.trim());
        }
        if (protocol != null && !protocol.isBlank()) {
            where.append(" AND j.protocol = ?");
            args.add(protocol.trim());
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (j.request_json ILIKE ? OR t.title ILIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (from != null && from > 0) {
            where.append(" AND j.created_at >= ?");
            args.add(from);
        }
        if (to != null && to > 0) {
            where.append(" AND j.created_at <= ?");
            args.add(to);
        }

        String fromClause = " FROM jobs j LEFT JOIN threads t ON t.id = j.thread_id"
                + " LEFT JOIN providers p ON p.id = j.provider_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + fromClause + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeLimit);
        pageArgs.add(safeOffset);
        List<RequestRow> rows = jdbc.query("""
                SELECT j.id, j.thread_id, j.protocol, j.mode, j.status, j.created_at, j.replied_at,
                       j.via, j.provider_id, j.request_json, j.reply_content, j.forward_error,
                       t.title, t.model, t.source_ip, t.source_ua, t.source_client,
                       p.name AS provider_name
                """ + fromClause + where + " ORDER BY j.created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> new RequestRow(
                        rs.getString("id"), rs.getString("thread_id"), rs.getString("title"),
                        rs.getString("protocol"), rs.getString("mode"), rs.getString("status"),
                        rs.getString("model"), rs.getLong("created_at"), nullableLong(rs, "replied_at"),
                        rs.getString("via"), rs.getString("provider_name"),
                        rs.getString("source_ip"), rs.getString("source_ua"), rs.getString("source_client"),
                        preview(rs.getString("request_json"), 600),
                        preview(rs.getString("reply_content"), 300),
                        rs.getString("forward_error")),
                pageArgs.toArray());

        List<Map<String, Object>> list = new ArrayList<>();
        for (RequestRow row : rows) {
            Map<String, Object> item = Json.obj();
            item.put("id", row.id());
            item.put("threadId", row.threadId());
            item.put("threadTitle", row.threadTitle() == null ? "" : row.threadTitle());
            item.put("protocol", row.protocol());
            item.put("mode", row.mode());
            item.put("status", row.status());
            item.put("model", row.model() == null ? "" : row.model());
            item.put("createdAt", row.createdAt());
            item.put("repliedAt", row.repliedAt());
            item.put("via", row.via() == null ? "" : row.via());
            item.put("providerName", row.providerName() == null ? "" : row.providerName());
            item.put("sourceIp", row.sourceIp() == null ? "" : row.sourceIp());
            item.put("sourceUa", row.sourceUa() == null ? "" : row.sourceUa());
            item.put("sourceClient", row.sourceClient() == null ? "" : row.sourceClient());
            item.put("requestPreview", row.requestPreview());
            item.put("replyPreview", row.replyPreview());
            item.put("forwardError", row.forwardError());
            list.add(item);
        }

        Map<String, Object> body = Json.obj();
        body.put("total", total == null ? 0 : total);
        body.put("limit", safeLimit);
        body.put("offset", safeOffset);
        body.put("requests", list);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/admin/api/requests/{id}")
    public ResponseEntity<?> getRequest(@PathVariable String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT j.id, j.thread_id, j.protocol, j.mode, j.status, j.created_at, j.replied_at,
                       j.via, j.request_json, j.reply_content, j.forward_error, j.forward_error_at,
                       j.model AS billing_model, j.input_tokens, j.output_tokens,
                       j.cache_read_tokens, j.token_source,
                       t.title, t.model, t.source_ip, t.source_ua, t.source_client,
                       p.name AS provider_name
                FROM jobs j
                LEFT JOIN threads t ON t.id = j.thread_id
                LEFT JOIN providers p ON p.id = j.provider_id
                WHERE j.id = ?
                """, id);
        if (rows.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        Map<String, Object> row = rows.get(0);
        Map<String, Object> body = Json.obj();
        body.put("id", row.get("id"));
        body.put("threadId", row.get("thread_id"));
        body.put("threadTitle", row.get("title") == null ? "" : row.get("title"));
        body.put("protocol", row.get("protocol"));
        body.put("mode", row.get("mode"));
        body.put("status", row.get("status"));
        body.put("model", row.get("model") == null ? "" : row.get("model"));
        body.put("billingModel", row.get("billing_model") == null ? "" : row.get("billing_model"));
        body.put("createdAt", row.get("created_at"));
        body.put("repliedAt", row.get("replied_at"));
        body.put("via", row.get("via") == null ? "" : row.get("via"));
        body.put("providerName", row.get("provider_name") == null ? "" : row.get("provider_name"));
        body.put("sourceIp", row.get("source_ip") == null ? "" : row.get("source_ip"));
        body.put("sourceUa", row.get("source_ua") == null ? "" : row.get("source_ua"));
        body.put("sourceClient", row.get("source_client") == null ? "" : row.get("source_client"));
        body.put("requestJson", row.get("request_json") == null ? "" : row.get("request_json"));
        body.put("replyContent", row.get("reply_content"));
        body.put("forwardError", row.get("forward_error"));
        body.put("forwardErrorAt", row.get("forward_error_at"));
        body.put("inputTokens", row.get("input_tokens"));
        body.put("outputTokens", row.get("output_tokens"));
        body.put("cacheReadTokens", row.get("cache_read_tokens"));
        body.put("tokenSource", row.get("token_source"));
        return ResponseEntity.ok(body);
    }

    private static String preview(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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

        if (queue.reply(id, content, null, estimatedTokenRecord(id))) {
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

    /**
     * 人工回复的 token 估算：从 request_json 还原 prompt 算输入，回复内容算输出，
     * 标记 token_source = 'estimated'；解析失败时返回 null（不写 token 列）。
     */
    private PendingJob.TokenRecord estimatedTokenRecord(String jobId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT j.protocol, j.request_json, t.model FROM jobs j"
                            + " LEFT JOIN threads t ON t.id = j.thread_id WHERE j.id = ?", jobId);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            String protocol = String.valueOf(row.get("protocol"));
            JsonNode body = Json.tryParse(String.valueOf(row.get("request_json")));
            if (body == null) return null;

            ProtocolTypes.ParseResult<?> parsed = ProtocolTypes.ANTHROPIC.equals(protocol)
                    ? AnthropicProtocol.parse(body)
                    : OpenAiProtocol.parse(body);
            if (!parsed.ok()) return null;
            ProtocolTypes.Prompt prompt = parsed.value() instanceof AnthropicProtocol.ParsedRequest ar
                    ? ar.prompt()
                    : ((OpenAiProtocol.ParsedRequest) parsed.value()).prompt();

            int input = ProtocolTypes.estimateTokens(prompt.system() == null ? "" : prompt.system());
            for (ProtocolTypes.ChatMessage message : prompt.messages()) {
                input += ProtocolTypes.estimateTokens(message.content());
            }
            return new PendingJob.TokenRecord(
                    row.get("model") == null ? "" : String.valueOf(row.get("model")),
                    input, 0, 0, "estimated");
        } catch (RuntimeException e) {
            return null;
        }
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
                    20_000, new ProviderClient.AbortHandle()).content();
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

        String newSensitiveWords = null;
        if (body.has("sensitiveWords")) {
            JsonNode node = body.get("sensitiveWords");
            if (node == null || !node.isTextual()) {
                return ResponseEntity.status(400).body(Map.of(
                        "error", "invalid_sensitive_words", "message", "敏感词必须是字符串"));
            }
            newSensitiveWords = node.asText();
            if (newSensitiveWords.length() > 20_000) {
                return ResponseEntity.status(400).body(Map.of(
                        "error", "invalid_sensitive_words", "message", "敏感词列表不能超过 20000 个字符"));
            }
        }

        Boolean newSensitiveEnabled = null;
        if (body.has("sensitiveFilterEnabled")) {
            JsonNode node = body.get("sensitiveFilterEnabled");
            if (node == null || !node.isBoolean()) {
                return ResponseEntity.status(400).body(Map.of(
                        "error", "invalid_sensitive_filter", "message", "敏感词开关必须是布尔值"));
            }
            newSensitiveEnabled = node.asBoolean();
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
        if (newSensitiveWords != null) settings.setSensitiveWordsText(newSensitiveWords);
        if (newSensitiveEnabled != null) settings.setSensitiveFilterEnabled(newSensitiveEnabled);

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
        payload.put("sensitiveWords", settings.getSensitiveWordsText() == null
                ? "" : settings.getSensitiveWordsText());
        payload.put("sensitiveFilterEnabled", settings.isSensitiveFilterEnabled());
        return payload;
    }

    // ---- 模型单价 ----

    @GetMapping("/admin/api/model-prices")
    public ResponseEntity<?> listPrices() {
        return ResponseEntity.ok(Map.of("prices", modelPrices.listPrices()));
    }

    @PostMapping("/admin/api/model-prices")
    public ResponseEntity<?> createPrice(@RequestBody(required = false) byte[] rawBody) {
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        try {
            ModelPriceStore.ModelPrice price = modelPrices.createPrice(new ModelPriceStore.PriceInput(
                    text(body, "model"),
                    decimal(body, "inputPerM"),
                    decimal(body, "outputPerM"),
                    decimal(body, "cacheReadPerM"),
                    text(body, "currency")));
            return ResponseEntity.ok(Map.of("price", price));
        } catch (ModelPriceStore.PriceValidationException e) {
            return priceErrorReply(e);
        }
    }

    @PatchMapping("/admin/api/model-prices/{id}")
    public ResponseEntity<?> updatePrice(@PathVariable String id,
                                         @RequestBody(required = false) byte[] rawBody) {
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        if (body == null) body = Json.MAPPER.createObjectNode();
        ModelPriceStore.PricePatch patch = new ModelPriceStore.PricePatch(
                body.has("model") ? body.path("model").asText(null) : null,
                body.has("inputPerM") ? decimal(body, "inputPerM") : null,
                body.has("outputPerM") ? decimal(body, "outputPerM") : null,
                body.has("cacheReadPerM") ? decimal(body, "cacheReadPerM") : null,
                body.has("currency") ? body.path("currency").asText(null) : null);
        try {
            ModelPriceStore.ModelPrice price = modelPrices.updatePrice(id, patch);
            if (price == null) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
            return ResponseEntity.ok(Map.of("price", price));
        } catch (ModelPriceStore.PriceValidationException e) {
            return priceErrorReply(e);
        }
    }

    @DeleteMapping("/admin/api/model-prices/{id}")
    public ResponseEntity<?> deletePrice(@PathVariable String id) {
        if (!modelPrices.deletePrice(id)) return ResponseEntity.status(404).body(Map.of("error", "not_found"));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<?> priceErrorReply(ModelPriceStore.PriceValidationException e) {
        int status = "price_exists".equals(e.code()) ? 409 : 400;
        return ResponseEntity.status(status).body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    private static BigDecimal decimal(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null) return null;
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // ---- 用量统计 ----

    /**
     * 用量聚合：汇总卡 + 按天时序 + 按模型/厂商/来源分组。
     * 只统计 token_source 非空的行（v3 迁移前的旧行不参与）；花费按单价表匹配，
     * 没配价的模型该部分不计费（unpriced 单列返回）。
     */
    @GetMapping("/admin/api/usage")
    public ResponseEntity<?> usage(@RequestParam(required = false) Long from,
                                   @RequestParam(required = false) Long to) {
        long now = System.currentTimeMillis();
        long start = from != null && from > 0 ? from : 0;
        long end = to != null && to > 0 ? to : now + 86_400_000L;

        List<Object> args = new ArrayList<>(List.of(start, end));

        // 汇总（含命中单价的行数）
        List<Map<String, Object>> totalsRows = jdbc.queryForList("""
                SELECT COUNT(*) AS requests,
                       COALESCE(SUM(j.input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(j.output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(j.cache_read_tokens), 0) AS cache_read_tokens
                FROM jobs j
                WHERE j.token_source IS NOT NULL AND j.created_at >= ? AND j.created_at <= ?
                """, args.toArray());
        Map<String, Object> totalsRow = totalsRows.get(0);
        long requests = ((Number) totalsRow.get("requests")).longValue();
        long inputTokens = ((Number) totalsRow.get("input_tokens")).longValue();
        long outputTokens = ((Number) totalsRow.get("output_tokens")).longValue();
        long cacheReadTokens = ((Number) totalsRow.get("cache_read_tokens")).longValue();

        // 分组行（模型/厂商/来源统一形状），花费在 Java 侧按单价表计算
        List<Map<String, Object>> byModel = usageGroups("j.model", args);
        List<Map<String, Object>> byProvider = usageGroups("COALESCE(j.via, '人工回复')", args);
        List<Map<String, Object>> bySource = usageGroups(
                "COALESCE(NULLIF(t.source_client, ''), '未知来源')", args);

        // 按天时序
        List<Map<String, Object>> daily = jdbc.queryForList("""
                SELECT to_char(to_timestamp(j.created_at / 1000.0) AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day,
                       COUNT(*) AS requests,
                       COALESCE(SUM(j.input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(j.output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(j.cache_read_tokens), 0) AS cache_read_tokens
                FROM jobs j
                WHERE j.token_source IS NOT NULL AND j.created_at >= ? AND j.created_at <= ?
                GROUP BY 1 ORDER BY 1
                """, args.toArray());

        Map<String, Object> body = Json.obj();
        Map<String, Object> totals = Json.obj();
        totals.put("requests", requests);
        totals.put("inputTokens", inputTokens);
        totals.put("outputTokens", outputTokens);
        totals.put("cacheReadTokens", cacheReadTokens);
        totals.put("cacheHitRate", inputTokens > 0
                ? (double) cacheReadTokens / inputTokens : 0.0);
        totals.put("costs", usageCosts(byModel));
        body.put("totals", totals);
        body.put("daily", daily);
        body.put("byModel", byModel);
        body.put("byProvider", byProvider);
        body.put("bySource", bySource);
        body.put("from", start);
        body.put("to", end);
        return ResponseEntity.ok(body);
    }

    /** 分组统计的公共形状：key / requests / 三类 token / 该组的估算花费（按币种分列）。 */
    private List<Map<String, Object>> usageGroups(String keyExpr, List<Object> args) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT %s AS name,
                       COUNT(*) AS requests,
                       COALESCE(SUM(j.input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(j.output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(j.cache_read_tokens), 0) AS cache_read_tokens
                FROM jobs j LEFT JOIN threads t ON t.id = j.thread_id
                WHERE j.token_source IS NOT NULL AND j.created_at >= ? AND j.created_at <= ?
                GROUP BY 1 ORDER BY requests DESC
                """.formatted(keyExpr), args.toArray());

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("name"));
            ModelPriceStore.ModelPrice price = modelPrices.findByModel(name);
            Map<String, Object> item = Json.obj();
            item.put("name", name);
            item.put("requests", ((Number) row.get("requests")).longValue());
            item.put("inputTokens", ((Number) row.get("input_tokens")).longValue());
            item.put("outputTokens", ((Number) row.get("output_tokens")).longValue());
            item.put("cacheReadTokens", ((Number) row.get("cache_read_tokens")).longValue());
            if (price != null) {
                item.put("inputCost", ModelPriceStore.cost(price.inputPerM(),
                        ((Number) row.get("input_tokens")).longValue() - ((Number) row.get("cache_read_tokens")).longValue()));
                item.put("cacheReadCost", ModelPriceStore.cost(price.cacheReadPerM(),
                        ((Number) row.get("cache_read_tokens")).longValue()));
                item.put("outputCost", ModelPriceStore.cost(price.outputPerM(),
                        ((Number) row.get("output_tokens")).longValue()));
                item.put("currency", price.currency());
            }
            out.add(item);
        }
        return out;
    }

    /** 把 byModel 各组的花费按币种合计；未配价的请求单列计数。 */
    private Map<String, Object> usageCosts(List<Map<String, Object>> byModel) {
        Map<String, Object> costs = Json.obj();
        long unpricedRequests = 0;
        for (Map<String, Object> group : byModel) {
            if (group.get("inputCost") == null) {
                unpricedRequests += (Long) group.get("requests");
                continue;
            }
            String currency = String.valueOf(group.get("currency"));
            BigDecimal total = (BigDecimal) costs.get(currency);
            if (total == null) total = BigDecimal.ZERO;
            for (String key : new String[]{"inputCost", "cacheReadCost", "outputCost"}) {
                BigDecimal part = (BigDecimal) group.get(key);
                if (part != null) total = total.add(part);
            }
            costs.put(currency, total);
        }
        costs.put("unpricedRequests", unpricedRequests);
        return costs;
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.path(field).isTextual() ? body.path(field).asText() : "";
    }
}
