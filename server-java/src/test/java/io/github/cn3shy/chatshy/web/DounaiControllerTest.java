package io.github.cn3shy.chatshy.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.github.cn3shy.chatshy.MockUpstream;
import io.github.cn3shy.chatshy.TestDb;
import io.github.cn3shy.chatshy.config.ChatShyProperties;
import io.github.cn3shy.chatshy.core.Json;
import io.github.cn3shy.chatshy.core.SettingsService;

class DounaiControllerTest {

    @Test
    void buildEndpointNormalization() {
        assertThat(DounaiController.buildEndpoint("http://127.0.0.1:8088/v1", "/chat/completions"))
                .isEqualTo("http://127.0.0.1:8088/v1/chat/completions");
        assertThat(DounaiController.buildEndpoint("http://127.0.0.1:8088", "/chat/completions"))
                .isEqualTo("http://127.0.0.1:8088/v1/chat/completions");
        assertThat(DounaiController.buildEndpoint("https://api.deepseek.com/v1/chat/completions", "/chat/completions"))
                .isEqualTo("https://api.deepseek.com/v1/chat/completions");
        assertThat(DounaiController.buildEndpoint("https://open.bigmodel.cn/api/paas/v4/", "/chat/completions"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions");
        assertThat(DounaiController.buildEndpoint("https://x/models", "/chat/completions"))
                .isEqualTo("https://x/v1/chat/completions");
    }

    @Test
    void proxiesSseAndInjectsCallerKey() throws Exception {
        try (TestDb db = new TestDb(); MockUpstream upstream = new MockUpstream()) {
            SettingsService settings = new SettingsService(db.jdbc());
            settings.setSetting(SettingsService.CALLER_KEY, "sk-chatshy-test");
            ChatShyProperties properties = properties(upstream.base() + "/v1", "");
            upstream.setHandler((request, out) -> {
                MockUpstream.writeSseHead(out);
                MockUpstream.writeSseData(out, MockUpstream.contentChunk("你好"));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            });

            DounaiController controller = new DounaiController(settings, properties, 8088);
            MockHttpServletResponse response = new MockHttpServletResponse();
            String body = Json.stringify(Map.of(
                    "messages", List.of(Map.of("role", "user", "content", "hi"))));
            controller.chat(body.getBytes(StandardCharsets.UTF_8), new MockHttpServletRequest(), response);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentType()).contains("text/event-stream");
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("你好").contains("[DONE]");

            assertThat(upstream.requests()).hasSize(1);
            MockUpstream.Req sent = upstream.requests().get(0);
            assertThat(sent.headers().get("authorization")).isEqualTo("Bearer sk-chatshy-test");
            assertThat(sent.headers().get("x-chatshy-caller")).isEqualTo("%E8%B1%86%E5%A5%B6");
            var payload = Json.parse(sent.body());
            assertThat(payload.path("model").asText()).isEqualTo("chatshy");
            assertThat(payload.path("stream").asBoolean()).isTrue();
            assertThat(payload.path("messages").get(0).path("content").asText()).isEqualTo("hi");
        }
    }

    @Test
    void systemPromptIsPrepended() throws Exception {
        try (TestDb db = new TestDb(); MockUpstream upstream = new MockUpstream()) {
            SettingsService settings = new SettingsService(db.jdbc());
            settings.setSetting(SettingsService.CALLER_KEY, "sk-chatshy-test");
            ChatShyProperties properties = properties(upstream.base() + "/v1", "你是豆奶");
            upstream.setHandler((request, out) -> {
                MockUpstream.writeSseHead(out);
                MockUpstream.writeSseData(out, MockUpstream.contentChunk("ok"));
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            });

            DounaiController controller = new DounaiController(settings, properties, 8088);
            MockHttpServletResponse response = new MockHttpServletResponse();
            controller.chat(Json.stringify(Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))))
                    .getBytes(StandardCharsets.UTF_8), new MockHttpServletRequest(), response);

            var payload = Json.parse(upstream.requests().get(0).body());
            assertThat(payload.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(payload.path("messages").get(0).path("content").asText()).isEqualTo("你是豆奶");
            assertThat(payload.path("messages").get(1).path("content").asText()).isEqualTo("hi");
        }
    }

    @Test
    void rejectsEmptyMessages() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            settings.setSetting(SettingsService.CALLER_KEY, "sk-chatshy-test");
            DounaiController controller = new DounaiController(settings, properties("http://127.0.0.1:1/v1", ""), 8088);
            MockHttpServletResponse response = new MockHttpServletResponse();
            controller.chat("{\"messages\":[]}".getBytes(StandardCharsets.UTF_8),
                    new MockHttpServletRequest(), response);
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("messages 不能为空");
        }
    }

    @Test
    void upstreamErrorIsForwardedAsJson() throws Exception {
        try (TestDb db = new TestDb(); MockUpstream upstream = new MockUpstream()) {
            SettingsService settings = new SettingsService(db.jdbc());
            settings.setSetting(SettingsService.CALLER_KEY, "sk-chatshy-test");
            upstream.setHandler((request, out) ->
                    MockUpstream.writeJson(out, 401, "{\"error\":{\"message\":\"bad key\"}}"));

            DounaiController controller = new DounaiController(settings,
                    properties(upstream.base() + "/v1", ""), 8088);
            MockHttpServletResponse response = new MockHttpServletResponse();
            controller.chat(Json.stringify(Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))))
                    .getBytes(StandardCharsets.UTF_8), new MockHttpServletRequest(), response);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("上游返回 401").contains("bad key");
        }
    }

    private static ChatShyProperties properties(String upstreamBaseUrl, String systemPrompt) {
        return new ChatShyProperties(
                ":memory:", null, null, 60_000, "chatshy",
                15_000, 20_000, 1,
                new ChatShyProperties.Dounai(true, 8099, "127.0.0.1", "豆奶", systemPrompt, upstreamBaseUrl));
    }
}
