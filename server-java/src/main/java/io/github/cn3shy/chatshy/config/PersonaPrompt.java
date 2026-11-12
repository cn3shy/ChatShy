package io.github.cn3shy.chatshy.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 豆奶人设提示词：配置了 chatshy.dounai.system-prompt 时优先使用，
 * 否则使用打包在 classpath 下的默认人设。前端不再持有任何提示词。
 */
@Component
public class PersonaPrompt {
    private static final Logger log = LoggerFactory.getLogger(PersonaPrompt.class);
    private static final String DEFAULT_RESOURCE = "dounai/system-prompt.txt";

    private final String text;

    public PersonaPrompt(ChatShyProperties properties) {
        ChatShyProperties.Dounai dounai = properties == null ? null : properties.dounai();
        String configured = dounai == null ? null : dounai.systemPrompt();
        this.text = configured != null && !configured.isBlank()
                ? configured.trim()
                : readDefault();
    }

    /** 服务端人设；资源缺失时为空字符串（此时不注入 system 消息）。 */
    public String text() {
        return text;
    }

    private static String readDefault() {
        ClassPathResource resource = new ClassPathResource(DEFAULT_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            log.warn("读取默认人设失败：{}", DEFAULT_RESOURCE, e);
            return "";
        }
    }
}
