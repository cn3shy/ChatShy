package io.github.cn3shy.chatshy.web;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import io.github.cn3shy.chatshy.core.HttpUtil;
import io.github.cn3shy.chatshy.core.Json;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final SessionStore sessions;

    public AdminAuthInterceptor(SessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        if (sessions.verify(sessionToken(request))) return true;
        HttpUtil.writeJson(response, 401, java.util.Map.of("error", "unauthorized"));
        return false;
    }

    public static String sessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (SessionStore.SESSION_COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
}
