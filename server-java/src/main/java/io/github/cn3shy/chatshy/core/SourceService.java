package io.github.cn3shy.chatshy.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求来源（会话归并的作用域），与 Node 版 core/source.ts 逐条对齐。
 * 身份头 x-chatshy-caller 存在时指纹只由名字决定，与 IP 无关。
 */
public final class SourceService {
    public static final String CALLER_HEADER = "x-chatshy-caller";
    private static final int CALLER_MAX_LEN = 40;

    public record RequestSource(String ip, String ua, String caller, String client, String fingerprint) {
    }

    private static final Set<String> STABLE_IDENTITY = Set.of(
            "Claude Code", "Cherry Studio", "ChatBox", "LobeChat",
            "OpenAI Python SDK", "OpenAI Node SDK",
            "Anthropic Python SDK", "Anthropic Node SDK");

    private static final Pattern PERCENT = Pattern.compile("%[0-9a-fA-F]{2}");

    private SourceService() {
    }

    public static RequestSource requestSource(HttpServletRequest request) {
        String ip = HttpUtil.clientIp(request);
        String ua = header(request, "User-Agent");
        if (ua == null) ua = "";
        String caller = parseCaller(header(request, CALLER_HEADER));
        String client = caller.isEmpty() ? detectClient(ua) : caller;
        return new RequestSource(ip, ua, caller, client, fingerprintOf(ip, ua, client, caller));
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static String detectClient(String ua) {
        String s = ua == null ? "" : ua.trim();
        if (s.isEmpty()) return "未知来源";
        if (matches(s, "claude[-_ ]?(cli|code)")) return "Claude Code";
        if (matches(s, "cherry\\s*studio")) return "Cherry Studio";
        if (matches(s, "chatbox")) return "ChatBox";
        if (matches(s, "lobe")) return "LobeChat";
        if (matches(s, "openai/python|openai-python")) return "OpenAI Python SDK";
        if (matches(s, "openai/node")) return "OpenAI Node SDK";
        if (matches(s, "anthropic/python|anthropic-sdk-python")) return "Anthropic Python SDK";
        if (matches(s, "anthropic/node")) return "Anthropic Node SDK";
        if (matches(s, "^curl/")) return "curl";
        if (matches(s, "python-requests|python-httpx|httpx|aiohttp|python-urllib")) return "Python 脚本";
        if (matches(s, "axios|node-fetch|undici|got\\s*\\(|okhttp")) return "脚本客户端";
        if (matches(s, "postman")) return "Postman";
        if (matches(s, "insomnia")) return "Insomnia";
        if (matches(s, "mozilla/5\\.0|applewebkit|gecko")) return "浏览器";
        return "其他（" + HttpUtil.truncate(s, 40) + "）";
    }

    private static boolean matches(String value, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(value).find();
    }

    public static String fingerprintOf(String ip, String ua, String client) {
        return fingerprintOf(ip, ua, client, "");
    }

    public static String fingerprintOf(String ip, String ua, String client, String caller) {
        String identity;
        if (caller != null && !caller.isEmpty()) {
            identity = "caller:" + sha256Hex(caller);
        } else {
            identity = ip + " " + (STABLE_IDENTITY.contains(client) ? client : ua);
        }
        return sha256Hex(identity).substring(0, 32);
    }

    public static String parseCaller(String raw) {
        if (raw == null) return "";
        String cleaned = decodeHeaderText(raw)
                .replaceAll("[\\p{Cc}\\p{Cf}]", "")
                .replaceAll("(?U)\\s+", " ")
                .trim();
        int[] cps = cleaned.codePoints().toArray();
        int limit = Math.min(cps.length, CALLER_MAX_LEN);
        return new String(cps, 0, limit);
    }

    private static String decodeHeaderText(String raw) {
        if (PERCENT.matcher(raw).find()) {
            String decoded = percentDecode(raw);
            if (decoded != null) return decoded;
        }
        if (allCodePointsBelow(raw, 0x7f)) return raw;
        if (!allCodePointsBelow(raw, 0xff)) return raw;
        return new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private static boolean allCodePointsBelow(String value, int max) {
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (cp > max) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    /** decodeURIComponent 语义：%XX 按字节解码后整体 UTF-8；非法编码返回 null。 */
    private static String percentDecode(String raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < raw.length(); ) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) return null;
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return null;
                out.write((hi << 4) | lo);
                i += 3;
            } else {
                int cp = raw.codePointAt(i);
                byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                out.writeBytes(bytes);
                i += Character.charCount(cp);
            }
        }
        try {
            return out.toString(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return null;
        }
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
