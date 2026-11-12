package io.github.cn3shy.chatshy.web;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.Forwarder;
import io.github.cn3shy.chatshy.core.HttpUtil;
import io.github.cn3shy.chatshy.core.JobQueue;
import io.github.cn3shy.chatshy.core.Json;
import io.github.cn3shy.chatshy.core.PendingJob;
import io.github.cn3shy.chatshy.core.SettingsService;
import io.github.cn3shy.chatshy.core.SourceService;
import io.github.cn3shy.chatshy.core.SourceService.RequestSource;
import io.github.cn3shy.chatshy.core.StreamService;
import io.github.cn3shy.chatshy.core.ThreadStore;
import io.github.cn3shy.chatshy.protocol.AnthropicProtocol;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ParseResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
public class AnthropicController {
    private final SettingsService settings;
    private final ThreadStore threads;
    private final JobQueue queue;
    private final StreamService streams;
    private final Forwarder forwarder;

    public AnthropicController(SettingsService settings, ThreadStore threads, JobQueue queue,
                               StreamService streams, Forwarder forwarder) {
        this.settings = settings;
        this.threads = threads;
        this.queue = queue;
        this.streams = streams;
        this.forwarder = forwarder;
    }

    @PostMapping({"/v1/messages", "/v1/v1/messages"})
    public void messages(@RequestBody(required = false) byte[] rawBody,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!CallerAuth.requireCallerKey(settings, request)) {
            HttpUtil.writeJson(response, 401, AnthropicProtocol.invalidKey());
            return;
        }

        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        ParseResult<AnthropicProtocol.ParsedRequest> parsed = AnthropicProtocol.parse(body);
        if (!parsed.ok()) {
            HttpUtil.writeJson(response, 400, AnthropicProtocol.badRequest(parsed.message()));
            return;
        }

        AnthropicProtocol.ParsedRequest value = parsed.value();
        String model = value.prompt().model() == null || value.prompt().model().isEmpty()
                ? settings.getModelName() : value.prompt().model();
        RequestSource source = SourceService.requestSource(request);
        ThreadStore.ResolveResult resolved = threads.resolve(new ThreadStore.ResolveParams(
                source, ProtocolTypes.ANTHROPIC, model, value.prompt().system(), value.prompt().messages()));
        // IP 限流只针对新建会话：老会话正常聊天不受影响；超限则回滚刚建的会话再拒绝
        if (resolved.created() && threads.newThreadLimitReached(source.ip())) {
            threads.deleteThread(resolved.thread().id());
            HttpUtil.writeJson(response, 429, AnthropicProtocol.rateLimited());
            return;
        }
        if (resolved.thread().blacklisted()) {
            HttpUtil.writeJson(response, 403, AnthropicProtocol.blacklisted());
            return;
        }

        String requestJson = body == null || body.isNull() ? "{}" : body.toString();
        PendingJob job = queue.create(resolved.thread().id(), ProtocolTypes.ANTHROPIC,
                value.stream() ? "stream" : "non_stream", requestJson, settings.getDefaultTimeoutMs());
        threads.linkLatestUserMessageToJob(resolved.thread().id(), job.id());

        String id = AnthropicProtocol.newMessageId();
        int promptTokens = ProtocolTypes.estimateTokens(joinPrompt(value.prompt()));
        java.util.function.Consumer<String> onDelivered = content ->
                threads.appendMessage(resolved.thread().id(), "assistant", content, "api", job.id());

        if (value.stream()) {
            StreamService.Renderer renderer = new StreamService.Renderer() {
                @Override
                public String initial() {
                    return AnthropicProtocol.messageStartFrame(id, model, promptTokens);
                }

                @Override
                public String open() {
                    return AnthropicProtocol.messageOpenBlockFrame();
                }

                @Override
                public String delta(String text) {
                    return AnthropicProtocol.messageDeltaFrame(text);
                }

                @Override
                public java.util.List<String> finish(String fullText) {
                    return AnthropicProtocol.messageFinishFrames(new AnthropicProtocol.MessageParams(
                            id, model, fullText, promptTokens, ProtocolTypes.estimateTokens(fullText)));
                }

                @Override
                public String ping() {
                    return AnthropicProtocol.pingFrame();
                }

                @Override
                public String error() {
                    return AnthropicProtocol.eventFrame("error", AnthropicProtocol.timeout());
                }

                @Override
                public String failure() {
                    return AnthropicProtocol.eventFrame("error", AnthropicProtocol.upstreamFailure());
                }
            };
            streams.serveStream(response, job, renderer, onDelivered,
                    () -> forwarder.maybeAutoForward(job, value.prompt()));
            return;
        }

        forwarder.maybeAutoForward(job, value.prompt());
        streams.serveNonStream(response, job, content ->
                        AnthropicProtocol.renderMessage(new AnthropicProtocol.MessageParams(
                                id, model, content, promptTokens, ProtocolTypes.estimateTokens(content))),
                AnthropicProtocol.timeout(), 529, onDelivered);
    }

    @PostMapping({"/v1/messages/count_tokens", "/v1/v1/messages/count_tokens"})
    public ResponseEntity<?> countTokens(@RequestBody(required = false) byte[] rawBody,
                                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!CallerAuth.requireCallerKey(settings, request)) {
            HttpUtil.writeJson(response, 401, AnthropicProtocol.invalidKey());
            return null;
        }
        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        ParseResult<AnthropicProtocol.ParsedRequest> parsed = AnthropicProtocol.parse(body);
        if (!parsed.ok()) {
            HttpUtil.writeJson(response, 400, AnthropicProtocol.badRequest(parsed.message()));
            return null;
        }
        int tokens = ProtocolTypes.estimateTokens(joinPrompt(parsed.value().prompt()));
        return ResponseEntity.ok(Map.of("input_tokens", tokens));
    }

    private static String joinPrompt(ProtocolTypes.Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        sb.append(prompt.system() == null ? "" : prompt.system());
        for (ProtocolTypes.ChatMessage message : prompt.messages()) {
            sb.append('\n').append(message.content());
        }
        return sb.toString();
    }
}
