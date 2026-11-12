package io.github.cn3shy.chatshy.web;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import io.github.cn3shy.chatshy.protocol.OpenAiProtocol;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ParseResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
public class OpenAiController {
    private final SettingsService settings;
    private final ThreadStore threads;
    private final JobQueue queue;
    private final StreamService streams;
    private final Forwarder forwarder;

    public OpenAiController(SettingsService settings, ThreadStore threads, JobQueue queue,
                            StreamService streams, Forwarder forwarder) {
        this.settings = settings;
        this.threads = threads;
        this.queue = queue;
        this.streams = streams;
        this.forwarder = forwarder;
    }

    @PostMapping({"/v1/chat/completions", "/v1/v1/chat/completions"})
    public void chatCompletion(@RequestBody(required = false) byte[] rawBody,
                               HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!CallerAuth.requireCallerKey(settings, request)) {
            HttpUtil.writeJson(response, 401, OpenAiProtocol.invalidKey());
            return;
        }

        JsonNode body = Json.tryParse(Json.utf8(rawBody));
        ParseResult<OpenAiProtocol.ParsedRequest> parsed = OpenAiProtocol.parse(body);
        if (!parsed.ok()) {
            HttpUtil.writeJson(response, 400, OpenAiProtocol.badRequest(parsed.message()));
            return;
        }

        OpenAiProtocol.ParsedRequest value = parsed.value();
        String model = value.prompt().model() == null || value.prompt().model().isEmpty()
                ? settings.getModelName() : value.prompt().model();
        RequestSource source = SourceService.requestSource(request);
        ThreadStore.ResolveResult resolved = threads.resolve(new ThreadStore.ResolveParams(
                source, ProtocolTypes.OPENAI, model, value.prompt().system(), value.prompt().messages()));
        // IP 限流只针对新建会话：老会话正常聊天不受影响；超限则回滚刚建的会话再拒绝
        if (resolved.created() && threads.newThreadLimitReached(source.ip())) {
            threads.deleteThread(resolved.thread().id());
            HttpUtil.writeJson(response, 429, OpenAiProtocol.rateLimited());
            return;
        }
        if (resolved.thread().blacklisted()) {
            HttpUtil.writeJson(response, 403, OpenAiProtocol.blacklisted());
            return;
        }

        String requestJson = body == null || body.isNull() ? "{}" : body.toString();
        PendingJob job = queue.create(resolved.thread().id(), ProtocolTypes.OPENAI,
                value.stream() ? "stream" : "non_stream", requestJson, settings.getDefaultTimeoutMs());
        threads.linkLatestUserMessageToJob(resolved.thread().id(), job.id());

        String id = OpenAiProtocol.newCompletionId();
        long created = System.currentTimeMillis() / 1000;
        int promptTokens = ProtocolTypes.estimateTokens(joinPrompt(value.prompt()));
        java.util.function.Consumer<String> onDelivered = content ->
                threads.appendMessage(resolved.thread().id(), "assistant", content, "api", job.id());

        if (value.stream()) {
            StreamService.Renderer renderer = new StreamService.Renderer() {
                @Override
                public String initial() {
                    return OpenAiProtocol.completionRoleFrame(id, created, model);
                }

                @Override
                public String open() {
                    return "";
                }

                @Override
                public String delta(String text) {
                    return OpenAiProtocol.completionDeltaFrame(id, created, model, text);
                }

                @Override
                public java.util.List<String> finish(String fullText) {
                    return OpenAiProtocol.completionFinishFrames(new OpenAiProtocol.CompletionParams(
                            id, created, model, fullText, promptTokens,
                            ProtocolTypes.estimateTokens(fullText), value.includeUsage()));
                }

                @Override
                public String ping() {
                    return OpenAiProtocol.SSE_PING_COMMENT;
                }

                @Override
                public String error() {
                    return OpenAiProtocol.sseFrame(OpenAiProtocol.timeout());
                }

                @Override
                public String failure() {
                    return OpenAiProtocol.sseFrame(OpenAiProtocol.upstreamFailure());
                }
            };
            streams.serveStream(response, job, renderer, onDelivered,
                    () -> forwarder.maybeAutoForward(job, value.prompt()));
            return;
        }

        forwarder.maybeAutoForward(job, value.prompt());
        streams.serveNonStream(response, job, content ->
                        OpenAiProtocol.renderCompletion(new OpenAiProtocol.CompletionParams(
                                id, created, model, content, promptTokens,
                                ProtocolTypes.estimateTokens(content), false)),
                OpenAiProtocol.timeout(), 504, onDelivered);
    }

    @GetMapping("/v1/models")
    public ResponseEntity<?> models(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!CallerAuth.requireCallerKey(settings, request)) {
            HttpUtil.writeJson(response, 401, OpenAiProtocol.invalidKey());
            return null;
        }
        return ResponseEntity.ok(OpenAiProtocol.renderModels(settings.getModelName(),
                System.currentTimeMillis() / 1000));
    }

    @GetMapping("/v1/models/{id}")
    public ResponseEntity<?> model(@PathVariable String id, HttpServletRequest request,
                                   HttpServletResponse response) throws IOException {
        if (!CallerAuth.requireCallerKey(settings, request)) {
            HttpUtil.writeJson(response, 401, OpenAiProtocol.invalidKey());
            return null;
        }
        return ResponseEntity.ok(OpenAiProtocol.renderModel(settings.getModelName(),
                System.currentTimeMillis() / 1000));
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
