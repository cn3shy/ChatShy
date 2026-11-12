package io.github.cn3shy.chatshy.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个挂起中的请求。内存对象是 pending 状态的唯一真相（jobs 表只做审计）。 */
public final class PendingJob {
    public interface StreamSink {
        void delta(String text);

        void fail();
    }

    public sealed interface Outcome permits Replied, Timeout, Cancelled, Superseded {
    }

    public record Replied(String content) implements Outcome {
    }

    public record Timeout() implements Outcome {
    }

    public record Cancelled() implements Outcome {
    }

    public record Superseded() implements Outcome {
    }

    private final String id;
    private final String threadId;
    private final String protocol;
    private final String mode;
    private final long deadlineAt;
    private final CompletableFuture<Outcome> future = new CompletableFuture<>();
    private final AtomicBoolean streamed = new AtomicBoolean(false);

    private volatile String status = "pending";
    private volatile StreamSink sink;
    volatile ScheduledFuture<?> timer;

    PendingJob(String id, String threadId, String protocol, String mode, long deadlineAt) {
        this.id = id;
        this.threadId = threadId;
        this.protocol = protocol;
        this.mode = mode;
        this.deadlineAt = deadlineAt;
    }

    public String id() {
        return id;
    }

    public String threadId() {
        return threadId;
    }

    public String protocol() {
        return protocol;
    }

    public String mode() {
        return mode;
    }

    public long deadlineAt() {
        return deadlineAt;
    }

    public String status() {
        return status;
    }

    public CompletableFuture<Outcome> future() {
        return future;
    }

    public Outcome await() {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Cancelled();
        } catch (ExecutionException e) {
            throw new IllegalStateException("等待挂起请求失败", e);
        }
    }

    public void attachSink(StreamSink newSink) {
        this.sink = newSink;
    }

    public void detachSink(StreamSink oldSink) {
        if (this.sink == oldSink) this.sink = null;
    }

    /** 转发层把上游增量实时推给调用方；Job 已结束或调用方非流式时返回 false。 */
    public boolean pushDelta(String text) {
        if (!"pending".equals(status) || sink == null) return false;
        streamed.set(true);
        sink.delta(text);
        return true;
    }

    public void failStream() {
        StreamSink current = sink;
        if (!"pending".equals(status) || current == null) return;
        current.fail();
    }

    public boolean hasStreamed() {
        return streamed.get();
    }

    /** 仅由 JobQueue 调用：先落状态（DB/广播完成后再兑现 future）。 */
    synchronized boolean markComplete(Outcome outcome) {
        if (!"pending".equals(status)) return false;
        status = switch (outcome) {
            case Replied r -> "replied";
            case Timeout t -> "timeout";
            case Cancelled c -> "cancelled";
            case Superseded s -> "superseded";
        };
        return true;
    }

    /** 仅由 JobQueue 调用：兑现等待方，恰好一次。 */
    void resolve(Outcome outcome) {
        future.complete(outcome);
    }
}
