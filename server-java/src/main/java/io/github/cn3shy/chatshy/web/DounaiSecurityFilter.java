package io.github.cn3shy.chatshy.web;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.cn3shy.chatshy.config.ChatShyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 豆奶站点（8099）的安全响应头；管理端（8088）不受影响。 */
@Component
public class DounaiSecurityFilter extends OncePerRequestFilter {
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "img-src 'self' data: blob:",
            "connect-src 'self'",
            "style-src 'self'",
            "script-src 'self'",
            "base-uri 'none'",
            "form-action 'none'",
            "frame-ancestors 'none'");

    private final ChatShyProperties properties;

    public DounaiSecurityFilter(ChatShyProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        ChatShyProperties.Dounai dounai = properties.dounai();
        return dounai == null || !dounai.enabled() || request.getLocalPort() != dounai.port();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", CSP);
        filterChain.doFilter(request, response);
    }
}
