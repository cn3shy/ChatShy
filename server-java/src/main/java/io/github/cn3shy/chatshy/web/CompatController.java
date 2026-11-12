package io.github.cn3shy.chatshy.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** 客户端兼容层：健康检查、连通性探测、Claude Code 遥测吞掉。 */
@RestController
public class CompatController {

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of("ok", true);
    }

    @GetMapping({"/api/hello", "/v1/api/hello"})
    public Map<String, Object> hello() {
        return Map.of("ok", true);
    }

    // Claude Code 遥测路径：返回 404 会触发客户端重试风暴，一律 200 吞掉
    @RequestMapping(value = {"/api/event_logging", "/api/event_logging/**"},
            method = {RequestMethod.POST, RequestMethod.PUT})
    public Map<String, Object> telemetry() {
        return Map.of("ok", true);
    }
}
