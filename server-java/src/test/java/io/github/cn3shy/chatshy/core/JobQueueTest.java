package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.TestDb;
import io.github.cn3shy.chatshy.core.PendingJob.Cancelled;
import io.github.cn3shy.chatshy.core.PendingJob.Outcome;
import io.github.cn3shy.chatshy.core.PendingJob.Replied;
import io.github.cn3shy.chatshy.core.PendingJob.Superseded;
import io.github.cn3shy.chatshy.core.PendingJob.Timeout;
import io.github.cn3shy.chatshy.core.SourceService.RequestSource;
import io.github.cn3shy.chatshy.core.ThreadStore.ResolveParams;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ChatMessage;

class JobQueueTest {
    private TestDb db;
    private ThreadStore threads;
    private OperatorHub hub;
    private JobQueue queue;
    private final List<String> frames = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        db = new TestDb();
        threads = new ThreadStore(db.jdbc());
        hub = new OperatorHub(TestDb.properties());
        hub.addClient(frames::add);
        queue = new JobQueue(db.jdbc(), hub);
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
        db.close();
    }

    private String threadId(String firstUser) {
        RequestSource source = new RequestSource("127.0.0.1", "test/1.0", "", "测试客户端", "fp-1");
        return threads.resolve(new ResolveParams(source, "openai", "m", null,
                List.of(new ChatMessage("user", firstUser)))).thread().id();
    }

    private String jobStatus(String jobId) {
        return db.jdbc().queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId);
    }

    private List<String> eventTypes() {
        List<String> types = new ArrayList<>();
        for (String frame : frames) {
            JsonNode node = Json.tryParse(frame.substring("data: ".length()).trim());
            if (node != null) types.add(node.path("type").asText());
        }
        return types;
    }

    @Test
    void replyCompletesExactlyOnceAndPersists() {
        String threadId = threadId("u");
        PendingJob job = queue.create(threadId, "openai", "non_stream", "{}", 60_000);

        assertThat(queue.reply(job.id(), "你好", null)).isTrue();
        Outcome outcome = job.await();
        assertThat(outcome).isInstanceOf(Replied.class);
        assertThat(((Replied) outcome).content()).isEqualTo("你好");
        assertThat(jobStatus(job.id())).isEqualTo("replied");
        assertThat(db.jdbc().queryForObject("SELECT reply_content FROM jobs WHERE id = ?", String.class, job.id()))
                .isEqualTo("你好");
        assertThat(db.jdbc().queryForObject("SELECT replied_at FROM jobs WHERE id = ?", Long.class, job.id()))
                .isNotNull();

        assertThat(queue.reply(job.id(), "again", null)).isFalse();
        assertThat(queue.ignore(job.id())).isFalse();
        assertThat(queue.cancel(job.id())).isFalse();
        assertThat(eventTypes()).containsExactly("job.created", "job.replied");
    }

    @Test
    void timeoutFiresAtDeadline() {
        String threadId = threadId("u");
        PendingJob job = queue.create(threadId, "openai", "stream", "{}", 100);
        assertThat(job.await()).isInstanceOf(Timeout.class);
        assertThat(jobStatus(job.id())).isEqualTo("timeout");
        assertThat(eventTypes()).containsExactly("job.created", "job.timeout");
    }

    @Test
    void ignoreLooksLikeTimeout() {
        String threadId = threadId("u");
        PendingJob job = queue.create(threadId, "anthropic", "non_stream", "{}", 60_000);
        assertThat(queue.ignore(job.id())).isTrue();
        assertThat(job.await()).isInstanceOf(Timeout.class);
        assertThat(jobStatus(job.id())).isEqualTo("timeout");
    }

    @Test
    void cancelOnClientDisconnect() {
        String threadId = threadId("u");
        PendingJob job = queue.create(threadId, "openai", "stream", "{}", 60_000);
        assertThat(queue.cancel(job.id())).isTrue();
        assertThat(job.await()).isInstanceOf(Cancelled.class);
        assertThat(jobStatus(job.id())).isEqualTo("cancelled");
        assertThat(queue.reply(job.id(), "late", null)).isFalse();
    }

    @Test
    void newRequestSupersedesOldPendingOne() {
        String threadId = threadId("u");
        PendingJob first = queue.create(threadId, "openai", "non_stream", "{}", 60_000);
        PendingJob second = queue.create(threadId, "openai", "non_stream", "{}", 60_000);

        assertThat(first.await()).isInstanceOf(Superseded.class);
        assertThat(jobStatus(first.id())).isEqualTo("superseded");
        assertThat(second.status()).isEqualTo("pending");
        assertThat(queue.reply(second.id(), "ok", null)).isTrue();
        assertThat(second.await()).isInstanceOf(Replied.class);
        assertThat(eventTypes()).containsExactly("job.created", "job.superseded", "job.created", "job.replied");
    }

    @Test
    void failForThreadOnlyAffectsThatThread() {
        String t1 = threadId("u1");
        String t2 = threadId("u2");
        PendingJob a = queue.create(t1, "openai", "non_stream", "{}", 60_000);
        PendingJob b = queue.create(t2, "openai", "non_stream", "{}", 60_000);

        assertThat(queue.failForThread(t1)).isEqualTo(1);
        assertThat(a.await()).isInstanceOf(Timeout.class);
        assertThat(jobStatus(a.id())).isEqualTo("timeout");
        assertThat(b.status()).isEqualTo("pending");
        assertThat(queue.failForThread(t1)).isZero();
        assertThat(queue.countPendingForThread(t2)).isEqualTo(1);
    }
}
