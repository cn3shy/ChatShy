package io.github.cn3shy.chatshy.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import io.github.cn3shy.chatshy.config.ChatShyProperties;
import jakarta.servlet.http.HttpServletRequest;

/** 按端口分站点：8088 操作台首页，8099 豆奶首页。 */
@Controller
public class PageController {
    private final ChatShyProperties properties;

    public PageController(ChatShyProperties properties) {
        this.properties = properties;
    }

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<?> index(HttpServletRequest request) {
        ChatShyProperties.Dounai dounai = properties.dounai();
        boolean isDounai = dounai != null && dounai.enabled() && request.getLocalPort() == dounai.port();
        Path index = Path.of(isDounai ? "dounai/dist/index.html" : "web/dist/index.html");
        if (!Files.isRegularFile(index)) {
            String hint = isDounai
                    ? "豆奶前端尚未构建：请先执行 npm run dounai:web:build"
                    : "操作台前端尚未构建：请先执行 npm run web:build";
            return ResponseEntity.status(503)
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                    .body(hint);
        }
        try {
            byte[] body = Files.readAllBytes(index);
            return ResponseEntity.ok()
                    .cacheControl(org.springframework.http.CacheControl.noCache())
                    .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                    .body(body);
        } catch (IOException e) {
            return ResponseEntity.status(500)
                    .cacheControl(org.springframework.http.CacheControl.noStore())
                    .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                    .body("读取前端页面失败");
        }
    }
}
