package io.github.cn3shy.chatshy.web;

import io.github.cn3shy.chatshy.core.SettingsService;
import jakarta.servlet.http.HttpServletRequest;

/** 调用方 Key 校验：同时接受 Authorization: Bearer 与 x-api-key。 */
public final class CallerAuth {
    private CallerAuth() {
    }

    public static boolean requireCallerKey(SettingsService settings, HttpServletRequest request) {
        String expected = settings.getCallerKey();
        if (expected == null) return false; // 尚未初始化时拒绝一切，避免"开门"
        String bearer = bearerToken(request);
        if (bearer != null && settings.callerKeyMatches(expected, bearer)) return true;
        String apiKey = request.getHeader("x-api-key");
        if (apiKey != null && !apiKey.isEmpty() && settings.callerKeyMatches(expected, apiKey)) return true;
        return false;
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
