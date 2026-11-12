package io.github.cn3shy.chatshy.protocol;

import java.util.List;

public final class ProtocolTypes {
    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";

    private ProtocolTypes() {
    }

    public record ChatMessage(String role, String content) {
    }

    /** 内部统一模型：协议适配层负责"协议形状 ↔ 此模型"的翻译。 */
    public record Prompt(String model, String system, List<ChatMessage> messages) {
    }

    public record ParseResult<T>(boolean ok, T value, String message) {
        public static <T> ParseResult<T> ok(T value) {
            return new ParseResult<>(true, value, null);
        }

        public static <T> ParseResult<T> fail(String message) {
            return new ParseResult<>(false, null, message);
        }
    }

    /** 粗略估算 token 数：CJK 1 字 ≈ 1 token，其余 4 字符 ≈ 1 token。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCjk(cp)) cjk++;
            i += Character.charCount(cp);
        }
        return cjk + (int) Math.ceil((text.length() - cjk) / 4.0);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3000 && cp <= 0x303F)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF00 && cp <= 0xFFEF);
    }
}
