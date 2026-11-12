package io.github.cn3shy.chatshy.core;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** HTTP 层小工具：客户端 IP、HTTPS 判定、JSON 响应。 */
public final class HttpUtil {
    private static final Pattern IPV4_MAPPED = Pattern.compile("^::ffff:(\\d{1,3}(?:\\.\\d{1,3}){3})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private HttpUtil() {
    }

    public static void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=utf-8");
        response.getOutputStream().write(Json.MAPPER.writeValueAsBytes(body));
        response.flushBuffer();
    }

    public static void writeText(HttpServletResponse response, int status, String text) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain; charset=utf-8");
        response.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }

    /**
     * 与 Node 版 core/source.ts 的 clientIpOf 对齐：
     * 直连对端是公网地址时忽略 X-Real-IP / X-Forwarded-For，挡住伪造；
     * 对端是本机/内网（反代）时优先取 XFF 第一段（Fastify trustProxy 的行为），
     * 没有 XFF 再回落到 X-Real-IP。
     */
    public static String clientIp(HttpServletRequest request) {
        String peer = normalizeIp(request.getRemoteAddr());
        if (isPrivateOrLoopback(peer)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",", 2)[0].trim();
                if (!first.isEmpty()) return normalizeIp(first);
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank()) return normalizeIp(real);
        }
        return peer;
    }

    public static boolean isHttps(HttpServletRequest request) {
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto != null && !proto.isBlank()) {
            return "https".equalsIgnoreCase(proto.split(",", 2)[0].trim());
        }
        return request.isSecure();
    }

    public static String normalizeIp(String ip) {
        if (ip == null) return "";
        String trimmed = ip.trim();
        Matcher m = IPV4_MAPPED.matcher(trimmed);
        return m.matches() ? m.group(1) : trimmed;
    }

    public static boolean isPrivateOrLoopback(String ip) {
        if (ip == null) return false;
        String value = ip.trim();
        if (value.equals("::1") || value.equals("127.0.0.1")) return true;
        String lower = value.toLowerCase();
        if (lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80")) return true;
        Matcher m = IPV4.matcher(value);
        if (!m.matches()) return false;
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 168) || (a == 169 && b == 254);
    }

    /** 与 encodeURIComponent 对齐：空格用 %20，不用 +。 */
    public static String encodeCallerHeader(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
