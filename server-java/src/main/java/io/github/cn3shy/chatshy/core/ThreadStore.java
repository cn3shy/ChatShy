package io.github.cn3shy.chatshy.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import io.github.cn3shy.chatshy.core.SourceService.RequestSource;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ChatMessage;

/** 会话归并与消息落库，与 Node 版 core/threads.ts 对齐。 */
@Service
public class ThreadStore {
    public record ThreadRecord(String id, String title, String protocol, String model, String fingerprint,
                               String sourceIp, String sourceUa, String sourceClient, String sourceFingerprint,
                               boolean blacklisted, long createdAt, long updatedAt) {
    }

    public record MessageRecord(String id, String threadId, String role, String content, String source,
                                String jobId, long createdAt) {
    }

    public record ResolveParams(RequestSource source, String protocol, String model, String system,
                                List<ChatMessage> messages) {
    }

    public record ResolveResult(ThreadRecord thread, List<MessageRecord> appended, boolean created) {
    }

    private static final RowMapper<ThreadRecord> THREAD_MAPPER = (rs, i) -> new ThreadRecord(
            rs.getString("id"), rs.getString("title"), rs.getString("protocol"), rs.getString("model"),
            rs.getString("fingerprint"), rs.getString("source_ip"), rs.getString("source_ua"),
            rs.getString("source_client"), rs.getString("source_fingerprint"),
            rs.getInt("blacklisted") != 0, rs.getLong("created_at"), rs.getLong("updated_at"));

    private static final RowMapper<MessageRecord> MESSAGE_MAPPER = (rs, i) -> new MessageRecord(
            rs.getString("id"), rs.getString("thread_id"), rs.getString("role"), rs.getString("content"),
            rs.getString("source"), rs.getString("job_id"), rs.getLong("created_at"));

    private final JdbcTemplate jdbc;
    private final int newThreadLimit;
    private final long newThreadWindowMs;

    public ThreadStore(JdbcTemplate jdbc) {
        this(jdbc, 5, 60_000);
    }

    @Autowired
    public ThreadStore(JdbcTemplate jdbc,
                       @Value("${chatshy.new-thread-limit:5}") int newThreadLimit,
                       @Value("${chatshy.new-thread-window-ms:60000}") long newThreadWindowMs) {
        this.jdbc = jdbc;
        this.newThreadLimit = newThreadLimit;
        this.newThreadWindowMs = newThreadWindowMs;
    }

    public ResolveResult resolve(ResolveParams params) {
        long now = System.currentTimeMillis();
        String firstUser = params.messages().stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .findFirst().orElse("");
        String fingerprint = contentFingerprint(params.system() == null ? "" : params.system(), firstUser);

        List<ThreadRecord> candidates = jdbc.query(
                "SELECT * FROM threads WHERE source_fingerprint = ? ORDER BY updated_at DESC, seq DESC",
                THREAD_MAPPER, params.source().fingerprint());

        ThreadRecord matched = null;
        List<MessageRecord> matchedSeq = List.of();
        int matchedLen = -1;

        for (ThreadRecord t : candidates) {
            List<MessageRecord> seq = apiMessagesOf(t.id());
            if (seq.size() > params.messages().size()) continue;
            if (seq.size() > matchedLen && isPrefixOf(seq, params.messages())) {
                matched = t;
                matchedSeq = seq;
                matchedLen = seq.size();
            }
        }

        if (matched == null) {
            for (ThreadRecord t : candidates) {
                if (fingerprint.equals(t.fingerprint())) {
                    matched = t;
                    matchedSeq = apiMessagesOf(t.id());
                    break;
                }
            }
        }

        if (matched != null) {
            int skip = commonPrefixLength(matchedSeq, params.messages());
            List<MessageRecord> appended = insertApiMessages(matched.id(), params.messages().subList(skip, params.messages().size()), now);
            jdbc.update("UPDATE threads SET updated_at = ?, source_ip = ?, source_ua = ?, source_client = ? WHERE id = ?",
                    now, params.source().ip(), params.source().ua(), params.source().client(), matched.id());
            ThreadRecord refreshed = new ThreadRecord(
                    matched.id(), matched.title(), matched.protocol(), matched.model(), matched.fingerprint(),
                    params.source().ip(), params.source().ua(), params.source().client(),
                    matched.sourceFingerprint(), matched.blacklisted(), matched.createdAt(), now);
            return new ResolveResult(refreshed, appended, false);
        }

        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO threads (id, title, protocol, model, fingerprint,
                                     source_ip, source_ua, source_client, source_fingerprint,
                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, titleFrom(firstUser), params.protocol(), params.model(), fingerprint,
                params.source().ip(), params.source().ua(), params.source().client(), params.source().fingerprint(),
                now, now);
        if (params.system() != null && !params.system().isEmpty()) {
            insertRawMessage(id, "system", params.system(), "api", null, now);
        }
        List<MessageRecord> appended = insertApiMessages(id, params.messages(), now);
        ThreadRecord thread = getThread(id);
        return new ResolveResult(thread, appended, true);
    }

    public String contentFingerprint(String system, String firstUserContent) {
        return sha256Hex(system + "\u0000" + firstUserContent);
    }

    public boolean deleteThread(String id) {
        return jdbc.update("DELETE FROM threads WHERE id = ?", id) > 0;
    }

    /** 拉黑 / 解除拉黑：被拉黑的会话在网关入口直接被拒绝，不再产生新请求。 */
    public void setBlacklisted(String id, boolean blacklisted) {
        jdbc.update("UPDATE threads SET blacklisted = ? WHERE id = ?", blacklisted ? 1 : 0, id);
    }

    /** 同一 IP 的时间窗内最多新建会话数；limit <= 0 表示关闭限流。 */
    public boolean newThreadLimitReached(String ip) {
        if (newThreadLimit <= 0 || ip == null || ip.isEmpty()) return false;
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM threads WHERE source_ip = ? AND created_at > ?",
                Long.class, ip, System.currentTimeMillis() - newThreadWindowMs);
        return count != null && count >= newThreadLimit;
    }

    public ThreadRecord getThread(String id) {
        List<ThreadRecord> rows = jdbc.query("SELECT * FROM threads WHERE id = ?", THREAD_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<MessageRecord> listMessages(String threadId) {
        return jdbc.query("SELECT * FROM messages WHERE thread_id = ? ORDER BY created_at ASC, seq ASC",
                MESSAGE_MAPPER, threadId);
    }

    public MessageRecord appendMessage(String threadId, String role, String content, String source, String jobId) {
        long now = System.currentTimeMillis();
        String id = insertRawMessage(threadId, role, content, source, jobId, now);
        jdbc.update("UPDATE threads SET updated_at = ? WHERE id = ?", now, threadId);
        List<MessageRecord> rows = jdbc.query("SELECT * FROM messages WHERE id = ?", MESSAGE_MAPPER, id);
        return rows.get(0);
    }

    public void linkLatestUserMessageToJob(String threadId, String jobId) {
        jdbc.update("""
                UPDATE messages SET job_id = ?
                WHERE id = (SELECT id FROM messages WHERE thread_id = ? AND role = 'user' AND job_id IS NULL
                            ORDER BY created_at DESC, seq DESC LIMIT 1)
                """, jobId, threadId);
    }

    private List<MessageRecord> apiMessagesOf(String threadId) {
        return jdbc.query("""
                SELECT * FROM messages WHERE thread_id = ? AND source = 'api' AND role IN ('user', 'assistant')
                ORDER BY created_at ASC, seq ASC
                """, MESSAGE_MAPPER, threadId);
    }

    private List<MessageRecord> insertApiMessages(String threadId, List<ChatMessage> messages, long at) {
        List<MessageRecord> out = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            String id = insertRawMessage(threadId, message.role(), message.content(), "api", null, at);
            out.add(jdbc.queryForObject("SELECT * FROM messages WHERE id = ?", MESSAGE_MAPPER, id));
        }
        return out;
    }

    private String insertRawMessage(String threadId, String role, String content, String source, String jobId, long at) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO messages (id, thread_id, role, content, source, job_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, threadId, role, content, source, jobId, at);
        return id;
    }

    private static boolean isPrefixOf(List<MessageRecord> seq, List<ChatMessage> messages) {
        return commonPrefixLength(seq, messages) == seq.size();
    }

    private static int commonPrefixLength(List<MessageRecord> seq, List<ChatMessage> messages) {
        int max = Math.min(seq.size(), messages.size());
        int i = 0;
        while (i < max && seq.get(i).role().equals(messages.get(i).role())
                && seq.get(i).content().equals(messages.get(i).content())) {
            i++;
        }
        return i;
    }

    private static String titleFrom(String content) {
        String normalized = content.replaceAll("(?U)\\s+", " ").trim();
        return normalized.length() <= 50 ? normalized : normalized.substring(0, 50);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
