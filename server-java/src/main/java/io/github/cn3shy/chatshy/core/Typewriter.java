package io.github.cn3shy.chatshy.core;

import java.util.ArrayList;
import java.util.List;

/** 把整段文本切成逐帧下发的小片；按扩展字素切，避免劈开 emoji 与组合字符。 */
public final class Typewriter {
    /** 每一帧至少几个字素：太小则事件碎、太大则不像打字机 */
    private static final int GRAPHEMES_PER_FRAME = 2;
    /** 帧数上限；长回复靠加大每帧的字素量压帧数 */
    private static final int MAX_FRAMES = 400;
    /** 总时长预算；长回复自动加速 */
    public static final long BUDGET_MS = 9_000;

    private Typewriter() {
    }

    public static List<String> chunks(String content) {
        if (content == null || content.isEmpty()) return List.of();
        List<String> graphemes = graphemes(content);
        int perFrame = Math.max(GRAPHEMES_PER_FRAME, (int) Math.ceil(graphemes.size() / (double) MAX_FRAMES));
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < graphemes.size(); i += perFrame) {
            chunks.add(String.join("", graphemes.subList(i, Math.min(i + perFrame, graphemes.size()))));
        }
        return chunks;
    }

    /**
     * 简化的扩展字素切分：
     *  - 组合记号（Mn/Mc/Me）、变体选择符、emoji 肤色修饰符并入前一个字素；
     *  - ZWJ 连同其后的码点一起并入（覆盖 👨‍👩‍👧 这类序列）；
     *  - 两个区域指示符合成一个旗帜。
     */
    static List<String> graphemes(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int start = i;
            int first = text.codePointAt(i);
            i += Character.charCount(first);
            boolean regionalPairOpen = isRegionalIndicator(first);
            while (i < text.length()) {
                int next = text.codePointAt(i);
                int len = Character.charCount(next);
                if (next == 0x200D) { // ZWJ
                    i += len;
                    if (i < text.length()) {
                        int after = text.codePointAt(i);
                        i += Character.charCount(after);
                    }
                    continue;
                }
                if (isCombining(next) || isVariationSelector(next) || isEmojiModifier(next)) {
                    i += len;
                    continue;
                }
                if (regionalPairOpen && isRegionalIndicator(next)) {
                    i += len;
                    regionalPairOpen = false;
                    continue;
                }
                break;
            }
            out.add(text.substring(start, i));
        }
        return out;
    }

    private static boolean isCombining(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isVariationSelector(int cp) {
        return (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    private static boolean isEmojiModifier(int cp) {
        return cp >= 0x1F3FB && cp <= 0x1F3FF;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }
}
