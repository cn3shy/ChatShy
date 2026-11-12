package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TypewriterTest {
    private static final String ZWJ = String.valueOf((char) 0x200D);
    private static final String FAMILY = new String(Character.toChars(0x1F468)) + ZWJ
            + new String(Character.toChars(0x1F469)) + ZWJ + new String(Character.toChars(0x1F467));
    private static final String FLAG = new String(Character.toChars(0x1F1E8))
            + new String(Character.toChars(0x1F1F3));
    private static final String COMBINING = "e" + (char) 0x0301;

    @Test
    void chunksJoinBackToOriginal() {
        String text = "这是一段中文回复，mixed with English 123 与符号 🎉！";
        assertThat(String.join("", Typewriter.chunks(text))).isEqualTo(text);
    }

    @Test
    void emptyTextHasNoFrames() {
        assertThat(Typewriter.chunks("")).isEmpty();
    }

    @Test
    void shortReplyIsSplitIntoMultipleFrames() {
        List<String> chunks = Typewriter.chunks("六个中文字");
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(String.join("", chunks)).isEqualTo("六个中文字");
    }

    @Test
    void graphemeClustersAreNotSplit() {
        String text = FAMILY + FLAG + COMBINING;
        List<String> chunks = Typewriter.chunks(text);
        assertThat(String.join("", chunks)).isEqualTo(text);
        for (String chunk : chunks) {
            assertThat(chunk.endsWith(ZWJ)).isFalse();
        }
        assertThat(chunks).anyMatch(chunk -> chunk.contains(FAMILY));
        assertThat(chunks).anyMatch(chunk -> chunk.contains(FLAG));
        assertThat(chunks).anyMatch(chunk -> chunk.contains(COMBINING));
    }

    @Test
    void longReplyIsCappedAtFourHundredFrames() {
        String longText = "字".repeat(5000);
        List<String> chunks = Typewriter.chunks(longText);
        assertThat(chunks).hasSizeLessThanOrEqualTo(400);
        assertThat(String.join("", chunks)).isEqualTo(longText);
    }
}
