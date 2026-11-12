package io.github.cn3shy.chatshy.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 豆奶公开入口的敏感词过滤。
 * 词库优先读 settings.sensitive_words，留空时使用内置 dounai/sensitive-words.txt。
 * 结果缓存 5 秒，操作台修改后最多 5 秒生效。
 */
@Component
public class SensitiveWordFilter {
    private static final String DEFAULT_RESOURCE = "dounai/sensitive-words.txt";
    private static final long CACHE_TTL_MS = 5000;
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\p{Z}\\p{P}\\p{S}]+");

    private final SettingsService settings;
    private final List<String> defaults;
    private volatile Snapshot snapshot;

    public SensitiveWordFilter(SettingsService settings) {
        this.settings = settings;
        this.defaults = loadDefaults();
    }

    /** 返回命中的词；未命中返回 null。 */
    public String match(String text) {
        if (text == null || text.isEmpty()) return null;
        Snapshot snap = snapshot();
        if (!snap.enabled()) return null;

        String normalized = normalize(text);
        if (normalized.isEmpty()) return null;
        String compact = compact(normalized);

        for (Word word : snap.words()) {
            if (word.compactText() != null && compact.contains(word.compactText())) {
                return word.raw();
            }
            if (word.pattern() != null && word.pattern().matcher(normalized).find()) {
                return word.raw();
            }
        }
        return null;
    }

    private Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Snapshot current = snapshot;
        if (current != null && now - current.loadedAt() < CACHE_TTL_MS) return current;
        synchronized (this) {
            current = snapshot;
            if (current != null && now - current.loadedAt() < CACHE_TTL_MS) return current;
            current = load();
            snapshot = current;
            return current;
        }
    }

    private Snapshot load() {
        boolean enabled = !"0".equals(settings.getSetting(SettingsService.SENSITIVE_FILTER_ENABLED));
        String raw = settings.getSetting(SettingsService.SENSITIVE_WORDS);
        List<String> words = raw == null || raw.isBlank() ? defaults : splitWords(raw);

        List<Word> compiled = new ArrayList<>();
        for (String word : words) {
            String normalized = normalize(word);
            if (normalized.isEmpty()) continue;
            if (containsCjk(normalized)) {
                String compactWord = compact(normalized);
                if (compactWord.codePointCount(0, compactWord.length()) < 2) continue;
                compiled.add(new Word(word, compactWord, null));
            } else {
                String latin = normalized.replaceAll("\\s+", " ").trim();
                if (latin.length() < 3) continue;
                String body = Arrays.stream(latin.split(" "))
                        .filter(part -> !part.isEmpty())
                        .map(Pattern::quote)
                        .collect(Collectors.joining("\\s+"));
                Pattern pattern = Pattern.compile("(?U)(?<![a-z0-9])" + body + "(?![a-z0-9])");
                String compactWord = latin.length() >= 4 ? compact(latin) : null;
                compiled.add(new Word(word, compactWord, pattern));
            }
        }
        return new Snapshot(enabled, List.copyOf(compiled), System.currentTimeMillis());
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }

    private static String compact(String normalized) {
        return SEPARATORS.matcher(normalized).replaceAll("");
    }

    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if ((cp >= 0x3400 && cp <= 0x9FFF)
                    || (cp >= 0xF900 && cp <= 0xFAFF)
                    || (cp >= 0x3040 && cp <= 0x30FF)
                    || (cp >= 0xAC00 && cp <= 0xD7AF)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static List<String> splitWords(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("[\\r\\n,，]+")) {
            String word = line.trim();
            if (word.isEmpty() || word.startsWith("#")) continue;
            out.add(word);
        }
        return out;
    }

    private static List<String> loadDefaults() {
        ClassPathResource resource = new ClassPathResource(DEFAULT_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return splitWords(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        }
    }

    private record Snapshot(boolean enabled, List<Word> words, long loadedAt) {
    }

    private record Word(String raw, String compactText, Pattern pattern) {
    }
}
