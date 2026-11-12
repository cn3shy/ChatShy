package io.github.cn3shy.chatshy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import io.github.cn3shy.chatshy.core.PendingJob.Cancelled;
import io.github.cn3shy.chatshy.core.PendingJob.Outcome;
import io.github.cn3shy.chatshy.core.PendingJob.Replied;
import io.github.cn3shy.chatshy.core.PendingJob.Superseded;
import io.github.cn3shy.chatshy.core.PendingJob.Timeout;
import jakarta.annotation.PreDestroy;

/**
 * 挂起请求队列。内存是 pending 的唯一真相；jobs 表用于审计，重启时由迁移标记 dropped。
 * 与 Node 版 core/jobs.ts 的状态机与事件顺序保持一致。
 */
@Service
public class JobQueue {
    private final JdbcTemplate jdbc;
    private final OperatorHub hub;
    private final Map<String, PendingJob> jobs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chatshy-job-timeout");
        t.setDaemon(true);
        return t;
    });

    public JobQueue(JdbcTemplate jdbc, OperatorHub hub) {
        this.jdbc = jdbc;
        this.hub = hub;
    }

    public synchronized PendingJob create(String threadId, String protocol, String mode,
                                          String requestJson, long timeoutMs) {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        long deadlineAt = now + timeoutMs;

        // 同一会话里更早的挂起请求（客户端超时重试）一律被新请求取代
        for (PendingJob existing : List.copyOf(jobs.values())) {
            if (existing.threadId().equals(threadId) && "pending".equals(existing.status())) {
                finish(existing, new Superseded(), null);
            }
        }

        jdbc.update("""
                INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, reply_content, deadline_at, created_at, replied_at)
                VALUES (?, ?, ?, ?, 'pending', ?, NULL, ?, ?, NULL)
                """, id, threadId, protocol, mode, requestJson, deadlineAt, now);

        PendingJob job = new PendingJob(id, threadId, protocol, mode, deadlineAt);
        jobs.put(id, job);
        job.timer = scheduler.schedule(() -> finish(job, new Timeout(), null),
                Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        hub.broadcast(HubEvent.jobCreated(id, threadId, deadlineAt));
        return job;
    }

    public PendingJob get(String jobId) {
        return jobs.get(jobId);
    }

    public synchronized boolean reply(String jobId, String content, String via) {
        PendingJob job = jobs.get(jobId);
        if (job == null || !"pending".equals(job.status())) return false;
        finish(job, new Replied(content), via);
        return true;
    }

    public synchronized boolean ignore(String jobId) {
        PendingJob job = jobs.get(jobId);
        if (job == null || !"pending".equals(job.status())) return false;
        finish(job, new Timeout(), null);
        return true;
    }

    public synchronized boolean cancel(String jobId) {
        PendingJob job = jobs.get(jobId);
        if (job == null || !"pending".equals(job.status())) return false;
        finish(job, new Cancelled(), null);
        return true;
    }

    public synchronized int failForThread(String threadId) {
        int n = 0;
        for (PendingJob job : List.copyOf(jobs.values())) {
            if (job.threadId().equals(threadId) && "pending".equals(job.status())) {
                finish(job, new Timeout(), null);
                n++;
            }
        }
        return n;
    }

    public synchronized int countPendingForThread(String threadId) {
        int n = 0;
        for (PendingJob job : jobs.values()) {
            if (job.threadId().equals(threadId) && "pending".equals(job.status())) n++;
        }
        return n;
    }

    public List<PendingJob> listPending() {
        List<PendingJob> out = new ArrayList<>();
        for (PendingJob job : jobs.values()) {
            if ("pending".equals(job.status())) out.add(job);
        }
        return out;
    }

    private synchronized void finish(PendingJob job, Outcome outcome, String via) {
        if (!job.markComplete(outcome)) return;
        if (job.timer != null) job.timer.cancel(false);
        jobs.remove(job.id());

        String type = "job." + switch (outcome) {
            case Replied r -> "replied";
            case Timeout t -> "timeout";
            case Cancelled c -> "cancelled";
            case Superseded s -> "superseded";
        };

        try {
            // 先落库/广播，再兑现 future：调用方 await() 返回时状态与事件都已就绪
            if (outcome instanceof Replied replied) {
                jdbc.update("UPDATE jobs SET status = 'replied', reply_content = ?, replied_at = ?, via = ? WHERE id = ?",
                        replied.content(), System.currentTimeMillis(), via, job.id());
            } else {
                jdbc.update("UPDATE jobs SET status = ? WHERE id = ?", job.status(), job.id());
            }
            hub.broadcast(HubEvent.jobEnded(type, job.id(), job.threadId()));
        } finally {
            job.resolve(outcome);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
