package io.github.cn3shy.chatshy.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import io.github.cn3shy.chatshy.config.ChatShyProperties;
import io.github.cn3shy.chatshy.core.PendingJob.Cancelled;
import io.github.cn3shy.chatshy.core.PendingJob.Outcome;
import io.github.cn3shy.chatshy.core.PendingJob.Replied;
import io.github.cn3shy.chatshy.core.PendingJob.StreamSink;
import io.github.cn3shy.chatshy.core.PendingJob.Superseded;
import io.github.cn3shy.chatshy.core.PendingJob.Timeout;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;

/** 挂起响应的收尾：非流式写 JSON，流式按协议帧 / 打字机下发。 */
@Service
public class StreamService {
    public interface Renderer {
        String initial();

        String open();

        String delta(String text);

        List<String> finish(String fullText);

        String ping();

        String error();

        default String failure() {
            return error();
        }
    }

    private final JobQueue queue;
    private final long ssePingMs;
    private final long typewriterTickMs;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chatshy-sse-ping");
        t.setDaemon(true);
        return t;
    });

    public StreamService(JobQueue queue, ChatShyProperties properties) {
        this.queue = queue;
        this.ssePingMs = properties.ssePingMs() > 0 ? properties.ssePingMs() : 15_000;
        this.typewriterTickMs = properties.typewriterTickMs() >= 0 ? properties.typewriterTickMs() : 25;
    }

    public void serveNonStream(HttpServletResponse response, PendingJob job,
                               java.util.function.Function<String, Object> successBody,
                               Object timeoutBody, int timeoutStatus, java.util.function.Consumer<String> onDelivered) {
        Outcome outcome = job.await();
        try {
            if (outcome instanceof Replied replied) {
                onDelivered.accept(replied.content());
                HttpUtil.writeJson(response, 200, successBody.apply(replied.content()));
            } else if (outcome instanceof Timeout || outcome instanceof Superseded) {
                HttpUtil.writeJson(response, timeoutStatus, timeoutBody);
            }
            // Cancelled：连接已断开，无需也无法再写响应
        } catch (IOException ignored) {
            // 调用方已断开
        }
    }

    public void serveStream(HttpServletResponse response, PendingJob job, Renderer renderer,
                            java.util.function.Consumer<String> onDelivered, Runnable afterSink) {
        response.setStatus(200);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        OutputStream output;
        try {
            output = response.getOutputStream();
        } catch (IOException e) {
            queue.cancel(job.id());
            return;
        }

        SseConnection connection = new SseConnection(output, () -> queue.cancel(job.id()));
        if (!connection.send(renderer.initial())) {
            queue.cancel(job.id());
            connection.close();
            return;
        }

        java.util.concurrent.atomic.AtomicBoolean openSent = new java.util.concurrent.atomic.AtomicBoolean(false);
        StreamSink sink = new StreamSink() {
            @Override
            public void delta(String text) {
                if (connection.isClosed()) return;
                if (openSent.compareAndSet(false, true)) {
                    if (!connection.send(renderer.open())) return;
                }
                connection.send(renderer.delta(text));
            }

            @Override
            public void fail() {
                queue.cancel(job.id());
                connection.send(renderer.failure());
                connection.close();
            }
        };
        job.attachSink(sink);

        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            if (!connection.send(renderer.ping())) {
                queue.cancel(job.id());
            }
        }, ssePingMs, ssePingMs, TimeUnit.MILLISECONDS);

        try {
            afterSink.run();
            Outcome outcome = job.await();
            job.detachSink(sink);
            heartbeat.cancel(false);

            if (outcome instanceof Replied replied) {
                // 先落库再吐字：入库判定"回复确实发给了调用方"
                onDelivered.accept(replied.content());
                if (job.hasStreamed()) {
                    if (openSent.compareAndSet(false, true)) connection.send(renderer.open());
                    for (String frame : renderer.finish(replied.content())) connection.send(frame);
                } else {
                    typeOut(connection, renderer, replied.content(), openSent);
                }
            } else if (outcome instanceof Timeout || outcome instanceof Superseded) {
                connection.send(renderer.error());
            }
            // Cancelled：连接已断开
        } finally {
            job.detachSink(sink);
            heartbeat.cancel(false);
            connection.close();
        }
    }

    private void typeOut(SseConnection connection, Renderer renderer, String content,
                         java.util.concurrent.atomic.AtomicBoolean openSent) {
        if (openSent.compareAndSet(false, true)) {
            if (!connection.send(renderer.open())) return;
        }
        List<String> chunks = Typewriter.chunks(content);
        long gap = chunks.isEmpty() ? 0
                : Math.min(typewriterTickMs, Typewriter.BUDGET_MS / chunks.size());
        long started = System.nanoTime();
        for (int i = 0; i < chunks.size(); i++) {
            if (!connection.send(renderer.delta(chunks.get(i)))) return;
            if (gap > 0) {
                long target = started + (i + 1) * gap * 1_000_000L;
                long waitNs = target - System.nanoTime();
                if (waitNs > 0) {
                    try {
                        TimeUnit.NANOSECONDS.sleep(waitNs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        for (String frame : renderer.finish(content)) {
            if (!connection.send(frame)) return;
        }
    }

    private static final class SseConnection {
        private final OutputStream output;
        private final Runnable onClose;
        private boolean closed;

        SseConnection(OutputStream output, Runnable onClose) {
            this.output = output;
            this.onClose = onClose;
        }

        synchronized boolean send(String frame) {
            if (closed) return false;
            if (frame == null || frame.isEmpty()) return true;
            try {
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
                return true;
            } catch (IOException e) {
                closeLocked();
                return false;
            }
        }

        synchronized boolean isClosed() {
            return closed;
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                output.close();
            } catch (IOException ignored) {
                // 连接已断开
            }
        }

        private void closeLocked() {
            closed = true;
            try {
                output.close();
            } catch (IOException ignored) {
                // ignore
            }
            onClose.run();
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
