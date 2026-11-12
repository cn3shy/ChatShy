package io.github.cn3shy.chatshy.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/** 管理端登录会话（内存，重启需重新登录）+ 登录失败节流。 */
@Service
public class SessionStore {
    public static final String SESSION_COOKIE = "chatshy_session";
    private static final long SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int LOGIN_FAIL_LIMIT = 5;
    private static final long LOGIN_LOCK_MS = 60_000;

    private record SessionEntry(long expiresAt) {
    }

    private static final class FailureEntry {
        int count;
        long lockedUntil;
    }

    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, FailureEntry> failures = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String create() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new SessionEntry(System.currentTimeMillis() + SESSION_TTL_MS));
        return token;
    }

    public boolean verify(String token) {
        if (token == null) return false;
        SessionEntry entry = sessions.get(token);
        if (entry == null) return false;
        if (entry.expiresAt() <= System.currentTimeMillis()) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public void destroy(String token) {
        if (token != null) sessions.remove(token);
    }

    public boolean isLocked(String ip) {
        FailureEntry entry = failures.get(ip);
        return entry != null && entry.lockedUntil > System.currentTimeMillis();
    }

    public void registerFailure(String ip) {
        FailureEntry entry = failures.computeIfAbsent(ip, k -> new FailureEntry());
        synchronized (entry) {
            entry.count++;
            if (entry.count >= LOGIN_FAIL_LIMIT) {
                entry.lockedUntil = System.currentTimeMillis() + LOGIN_LOCK_MS;
                entry.count = 0;
            }
        }
    }

    public void resetFailures(String ip) {
        failures.remove(ip);
    }
}
