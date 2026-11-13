package io.github.cn3shy.chatshy.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.github.cn3shy.chatshy.core.PendingJob.Outcome;
import io.github.cn3shy.chatshy.core.ProviderClient.AbortHandle;
import io.github.cn3shy.chatshy.core.ProviderClient.ProviderError;
import io.github.cn3shy.chatshy.core.ProviderStore.Provider;
import io.github.cn3shy.chatshy.protocol.AnthropicProtocol;
import io.github.cn3shy.chatshy.protocol.OpenAiProtocol;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ParseResult;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.Prompt;
import jakarta.annotation.PreDestroy;

/** 第三方厂商转发编排：手动与自动共用同一条路径，并按 Job 单飞。 */
@Service
public class Forwarder {
    public enum Kind {
        REPLIED, IN_PROGRESS, PROVIDER_NOT_FOUND, BAD_REQUEST, JOB_ENDED, UPSTREAM_ERROR
    }

    public record ForwardResult(Kind kind, String providerName, String content, String message, String status) {
        static ForwardResult replied(String providerName, String content) {
            return new ForwardResult(Kind.REPLIED, providerName, content, null, null);
        }

        static ForwardResult inProgress() {
            return new ForwardResult(Kind.IN_PROGRESS, null, null, null, null);
        }

        static ForwardResult providerNotFound() {
            return new ForwardResult(Kind.PROVIDER_NOT_FOUND, null, null, null, null);
        }

        static ForwardResult badRequest(String message) {
            return new ForwardResult(Kind.BAD_REQUEST, null, null, message, null);
        }

        static ForwardResult jobEnded(String providerName, String status) {
            return new ForwardResult(Kind.JOB_ENDED, providerName, null, null, status);
        }

        static ForwardResult upstreamError(String providerName, String message) {
            return new ForwardResult(Kind.UPSTREAM_ERROR, providerName, null, message, null);
        }
    }

    private record InFlight(AbortHandle abort, String providerName, long startedAt) {
    }

    private record PromptResult(boolean ok, Prompt prompt, String message) {
    }

    private enum Source {
        AUTO, MANUAL
    }

    private final JdbcTemplate jdbc;
    private final ProviderStore providers;
    private final SettingsService settings;
    private final ProviderClient client;
    private final JobQueue queue;
    private final OperatorHub hub;
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService autoForwardExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public Forwarder(JdbcTemplate jdbc, ProviderStore providers, SettingsService settings,
                     ProviderClient client, JobQueue queue, OperatorHub hub) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.settings = settings;
        this.client = client;
        this.queue = queue;
        this.hub = hub;
    }

    public ForwardResult forwardPendingJob(PendingJob job, String providerId) {
        if (inFlight.containsKey(job.id())) return ForwardResult.inProgress();
        Provider provider = providers.getProvider(providerId);
        if (provider == null) return ForwardResult.providerNotFound();
        PromptResult prompt = promptFromJob(job.id());
        if (!prompt.ok()) return ForwardResult.badRequest(prompt.message());
        return runForward(job, provider, prompt.prompt(), Source.MANUAL);
    }

    /** 服务端在 Job 创建后调用；不依赖操作台在线。失败只记录/广播，Job 保持 pending。 */
    public void maybeAutoForward(PendingJob job, Prompt prompt) {
        boolean enabled;
        String providerId;
        try {
            enabled = settings.isAutoForwardEnabled();
            providerId = settings.getAutoForwardProviderId();
        } catch (RuntimeException e) {
            return;
        }
        if (!enabled) return;

        Provider provider = providerId.isEmpty() ? null : providers.getProvider(providerId);
        if (provider == null) {
            String message = "自动转发的厂商配置不存在，请在控制台重新选择";
            recordForwardError(job, null, message);
            hub.broadcast(HubEvent.forwardFailed(job.id(), job.threadId(), message));
            return;
        }
        autoForwardExecutor.submit(() -> {
            try {
                runForward(job, provider, prompt, Source.AUTO);
            } catch (RuntimeException ignored) {
                // runForward 内部已兜底；这里防御未预期异常
            }
        });
    }

    private ForwardResult runForward(PendingJob job, Provider provider, Prompt prompt, Source source) {
        AbortHandle abort = new AbortHandle();
        InFlight entry = new InFlight(abort, provider.name(), System.currentTimeMillis());
        if (inFlight.putIfAbsent(job.id(), entry) != null) return ForwardResult.inProgress();

        AtomicBoolean jobEnded = new AtomicBoolean(false);
        // Job 一旦以任何方式结束，立刻中止上游，避免白烧 token
        job.future().whenComplete((outcome, error) -> {
            jobEnded.set(true);
            abort.abort();
        });

        try {
            if (!"pending".equals(job.status())) {
                return ForwardResult.jobEnded(provider.name(), job.status());
            }

            String content;
            ProviderClient.TokenUsage usage;
            if ("stream".equals(job.mode())) {
                ProviderClient.ProviderResult result = client.callProviderStream(provider, prompt,
                        ProviderClient.PROVIDER_TIMEOUT_MS, abort, job::pushDelta);
                content = result.content();
                usage = result.usage();
            } else {
                ProviderClient.ProviderResult result = client.callProvider(provider, prompt,
                        ProviderClient.PROVIDER_TIMEOUT_MS, abort);
                content = result.content();
                usage = result.usage();
            }

            if (jobEnded.get() || !"pending".equals(job.status())) {
                return ForwardResult.jobEnded(provider.name(), job.status());
            }
            try {
                jdbc.update("UPDATE jobs SET provider_id = ? WHERE id = ?", provider.id(), job.id());
            } catch (RuntimeException ignored) {
                // 会话可能已被删除
            }
            if (queue.reply(job.id(), content, provider.name(), tokenRecord(provider, usage))) {
                clearForwardError(job.id(), provider);
                return ForwardResult.replied(provider.name(), content);
            }
            return ForwardResult.jobEnded(provider.name(), job.status());
        } catch (ProviderError e) {
            if (jobEnded.get() || !"pending".equals(job.status())
                    || e.code() == ProviderClient.ErrorCode.UPSTREAM_ABORTED) {
                return ForwardResult.jobEnded(provider.name(), job.status());
            }
            String message = e.getMessage() == null ? "转发失败：未知错误" : e.getMessage();
            recordForwardError(job, provider, message);
            if (job.hasStreamed()) job.failStream();
            if (source == Source.AUTO) {
                String shortMessage = message.length() <= 200 ? message : message.substring(0, 200) + "…";
                hub.broadcast(HubEvent.forwardFailed(job.id(), job.threadId(), shortMessage));
            }
            return ForwardResult.upstreamError(provider.name(), message);
        } catch (RuntimeException e) {
            if (jobEnded.get() || !"pending".equals(job.status())) {
                return ForwardResult.jobEnded(provider.name(), job.status());
            }
            String message = "转发失败：未知错误";
            recordForwardError(job, provider, message);
            if (job.hasStreamed()) job.failStream();
            if (source == Source.AUTO) {
                hub.broadcast(HubEvent.forwardFailed(job.id(), job.threadId(), message));
            }
            return ForwardResult.upstreamError(provider.name(), message);
        } finally {
            inFlight.remove(job.id());
        }
    }

    /** 从 jobs.request_json 还原调用方原始请求（比 thread 历史更准确）。 */
    PromptResult promptFromJob(String jobId) {
        java.util.List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT protocol, request_json FROM jobs WHERE id = ?", jobId);
        if (rows.isEmpty()) return new PromptResult(false, null, "请求记录不存在");
        Map<String, Object> row = rows.get(0);
        String protocol = String.valueOf(row.get("protocol"));
        String raw = String.valueOf(row.get("request_json"));

        com.fasterxml.jackson.databind.JsonNode body;
        try {
            body = Json.parse(raw);
        } catch (Exception e) {
            return new PromptResult(false, null, "请求记录已损坏，无法还原这次对话");
        }
        ParseResult<?> parsed = ProtocolTypes.ANTHROPIC.equals(protocol)
                ? AnthropicProtocol.parse(body)
                : OpenAiProtocol.parse(body);
        if (!parsed.ok()) {
            return new PromptResult(false, null, "无法还原请求内容：" + parsed.message());
        }
        Prompt prompt = parsed.value() instanceof AnthropicProtocol.ParsedRequest ar
                ? ar.prompt()
                : ((OpenAiProtocol.ParsedRequest) parsed.value()).prompt();
        return new PromptResult(true, prompt, null);
    }

    /** 把上游 usage 翻成 jobs 表的 token 列；上游没报时返回 null（不写 token 列）。 */
    private static PendingJob.TokenRecord tokenRecord(Provider provider, ProviderClient.TokenUsage usage) {
        if (usage == null || !usage.known()) return null;
        return new PendingJob.TokenRecord(
                provider.model(),
                Math.max(0, usage.inputTokens()),
                Math.max(0, usage.outputTokens()),
                Math.max(0, usage.cacheReadTokens()),
                "upstream");
    }

    private void recordForwardError(PendingJob job, Provider provider, String message) {
        try {
            jdbc.update("UPDATE jobs SET forward_error = ?, forward_error_at = ?, provider_id = ? WHERE id = ?",
                    message, System.currentTimeMillis(), provider == null ? null : provider.id(), job.id());
        } catch (RuntimeException ignored) {
            // 行已被级联删除
        }
    }

    private void clearForwardError(String jobId, Provider provider) {
        try {
            jdbc.update("UPDATE jobs SET forward_error = NULL, forward_error_at = NULL, provider_id = ? WHERE id = ?",
                    provider.id(), jobId);
        } catch (RuntimeException ignored) {
            // ignore
        }
    }

    @PreDestroy
    public void shutdown() {
        autoForwardExecutor.shutdownNow();
    }
}
