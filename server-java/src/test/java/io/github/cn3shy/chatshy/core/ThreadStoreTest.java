package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.cn3shy.chatshy.TestDb;
import io.github.cn3shy.chatshy.core.SourceService.RequestSource;
import io.github.cn3shy.chatshy.core.ThreadStore.ResolveParams;
import io.github.cn3shy.chatshy.protocol.ProtocolTypes.ChatMessage;

class ThreadStoreTest {
    private static RequestSource source(String fingerprint) {
        return new RequestSource("127.0.0.1", "test/1.0", "", "测试客户端", fingerprint);
    }

    private static final RequestSource SOURCE = source("fp-1");
    private static final RequestSource OTHER = new RequestSource(
            "10.0.0.1", "other/2.0", "", "另一个客户端", "fp-2");

    private static ChatMessage u(String content) {
        return new ChatMessage("user", content);
    }

    private static ChatMessage a(String content) {
        return new ChatMessage("assistant", content);
    }

    @Test
    void firstRequestCreatesThreadAndTitle() throws Exception {
        try (TestDb db = new TestDb()) {
            ThreadStore store = new ThreadStore(db.jdbc());
            String longText = "这是一条很长很长的消息".repeat(10);
            var result = store.resolve(new ResolveParams(SOURCE, "openai", "test-model", null, List.of(u(longText))));
            assertThat(result.thread().title()).hasSize(50);
            assertThat(result.appended()).hasSize(1);
            assertThat(result.appended().get(0).source()).isEqualTo("api");
            assertThat(result.thread().sourceIp()).isEqualTo("127.0.0.1");
            assertThat(result.thread().sourceClient()).isEqualTo("测试客户端");
            assertThat(result.thread().sourceFingerprint()).isEqualTo("fp-1");
        }
    }

    @Test
    void appendsOnlyNewMessages() throws Exception {
        try (TestDb db = new TestDb()) {
            ThreadStore store = new ThreadStore(db.jdbc());
            var first = store.resolve(new ResolveParams(SOURCE, "openai", "m", null, List.of(u("u1"))));
            store.appendMessage(first.thread().id(), "assistant", "a1", "api", null);

            var second = store.resolve(new ResolveParams(SOURCE, "openai", "m", null,
                    List.of(u("u1"), a("a1"), u("u2"))));
            assertThat(second.thread().id()).isEqualTo(first.thread().id());
            assertThat(second.appended()).extracting(ThreadStore.MessageRecord::content).containsExactly("u2");
            assertThat(store.listMessages(first.thread().id())).extracting(ThreadStore.MessageRecord::content)
                    .containsExactly("u1", "a1", "u2");
        }
    }

    @Test
    void refreshesSourceOnMerge() throws Exception {
        try (TestDb db = new TestDb()) {
            ThreadStore store = new ThreadStore(db.jdbc());
            var first = store.resolve(new ResolveParams(SOURCE, "openai", "m", null, List.of(u("u1"))));
            RequestSource upgraded = new RequestSource("127.0.0.1", "test/2.0", "", "测试客户端", "fp-1");
            var second = store.resolve(new ResolveParams(upgraded, "openai", "m", null,
                    List.of(u("u1"), a("a1"), u("u2"))));
            assertThat(second.thread().id()).isEqualTo(first.thread().id());
            assertThat(second.thread().sourceUa()).isEqualTo("test/2.0");
            assertThat(second.thread().sourceFingerprint()).isEqualTo("fp-1");
        }
    }

    @Test
    void sourceIsolationAndFingerprintFallback() throws Exception {
        try (TestDb db = new TestDb()) {
            ThreadStore store = new ThreadStore(db.jdbc());
            var first = store.resolve(new ResolveParams(SOURCE, "openai", "m", "You are Claude Code.",
                    List.of(u("你好"))));
            var other = store.resolve(new ResolveParams(OTHER, "openai", "m", "You are Claude Code.",
                    List.of(u("你好"))));
            assertThat(other.thread().id()).isNotEqualTo(first.thread().id());
            assertThat(store.listMessages(first.thread().id())).hasSize(2); // system + user

            // 前缀不匹配但指纹相同：归并到该会话，只追加分歧后的消息
            store.appendMessage(first.thread().id(), "assistant", "原回复", "api", null);
            var edited = store.resolve(new ResolveParams(SOURCE, "openai", "m", "You are Claude Code.",
                    List.of(u("你好"), a("被改过的回复"), u("继续"))));
            assertThat(edited.thread().id()).isEqualTo(first.thread().id());
            assertThat(edited.appended()).extracting(ThreadStore.MessageRecord::content)
                    .containsExactly("被改过的回复", "继续");
        }
    }

    @Test
    void consoleMessagesDoNotParticipateInPrefixMatching() throws Exception {
        try (TestDb db = new TestDb()) {
            ThreadStore store = new ThreadStore(db.jdbc());
            var first = store.resolve(new ResolveParams(SOURCE, "openai", "m", null, List.of(u("u1"))));
            store.appendMessage(first.thread().id(), "user", "控制台乱入", "console", null);
            store.appendMessage(first.thread().id(), "assistant", "控制台回复", "console", null);
            var second = store.resolve(new ResolveParams(SOURCE, "openai", "m", null,
                    List.of(u("u1"), a("a1"), u("u2"))));
            assertThat(second.thread().id()).isEqualTo(first.thread().id());
            assertThat(second.appended()).extracting(ThreadStore.MessageRecord::content)
                    .containsExactly("a1", "u2");
        }
    }

    @Test
    void linkAndDelete() throws Exception {
        try (TestDb db = new TestDb()) {
            ThreadStore store = new ThreadStore(db.jdbc());
            var result = store.resolve(new ResolveParams(SOURCE, "openai", "m", null, List.of(u("hi"))));
            store.linkLatestUserMessageToJob(result.thread().id(), "job-1");
            assertThat(store.listMessages(result.thread().id()).get(0).jobId()).isEqualTo("job-1");
            assertThat(store.deleteThread(result.thread().id())).isTrue();
            assertThat(store.getThread(result.thread().id())).isNull();
        }
    }
}
