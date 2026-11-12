package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.cn3shy.chatshy.TestDb;
import io.github.cn3shy.chatshy.core.ProviderStore.ProviderInput;
import io.github.cn3shy.chatshy.core.ProviderStore.ProviderPatch;
import io.github.cn3shy.chatshy.core.ProviderStore.ProviderValidationException;

class ProviderStoreTest {
    private static final ProviderInput INPUT = new ProviderInput(
            "DeepSeek", "https://api.deepseek.com/v1", "sk-test-key-1234", "deepseek-chat");

    @Test
    void normalizeBaseUrl() {
        assertThat(ProviderStore.normalizeBaseUrl("https://api.deepseek.com/v1/"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(ProviderStore.normalizeBaseUrl("https://api.deepseek.com/v1/chat/completions"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(ProviderStore.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(ProviderStore.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4/"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(ProviderStore.normalizeBaseUrl("HTTPS://API.Example.COM:443/v1"))
                .isEqualTo("https://api.example.com/v1");
        assertThat(ProviderStore.normalizeBaseUrl("http://example.com:80"))
                .isEqualTo("http://example.com");
    }

    @Test
    void rejectBadBaseUrls() {
        for (String bad : new String[]{
                "ftp://example.com/v1",
                "https://user:pass@example.com/v1",
                "https://example.com/v1?x=1",
                "https://example.com/v1#frag",
                "not a url"}) {
            assertThatThrownBy(() -> ProviderStore.normalizeBaseUrl(bad))
                    .isInstanceOf(ProviderValidationException.class);
        }
    }

    @Test
    void crud() throws Exception {
        try (TestDb db = new TestDb()) {
            ProviderStore store = new ProviderStore(db.jdbc());
            var created = store.createProvider(INPUT);
            assertThat(created.name()).isEqualTo("DeepSeek");
            assertThat(store.listProviders()).hasSize(1);

            var updated = store.updateProvider(created.id(), new ProviderPatch(
                    "DS", "https://api.deepseek.com", null, "deepseek-reasoner"));
            assertThat(updated.name()).isEqualTo("DS");
            assertThat(updated.baseUrl()).isEqualTo("https://api.deepseek.com");
            assertThat(updated.model()).isEqualTo("deepseek-reasoner");
            assertThat(updated.apiKey()).isEqualTo(INPUT.apiKey());

            var keepKey = store.updateProvider(created.id(), new ProviderPatch(null, null, "", null));
            assertThat(keepKey.apiKey()).isEqualTo(INPUT.apiKey());

            assertThat(store.deleteProvider(created.id())).isTrue();
            assertThat(store.getProvider(created.id())).isNull();
        }
    }

    @Test
    void validationAndMasking() throws Exception {
        try (TestDb db = new TestDb()) {
            ProviderStore store = new ProviderStore(db.jdbc());
            store.createProvider(INPUT);
            assertThatThrownBy(() -> store.createProvider(INPUT)).isInstanceOf(ProviderValidationException.class);
            assertThatThrownBy(() -> store.createProvider(new ProviderInput(" ", "https://x/v1", "k", "m")))
                    .isInstanceOf(ProviderValidationException.class);
            assertThatThrownBy(() -> store.createProvider(new ProviderInput("n", "https://x/v1", "", "m")))
                    .isInstanceOf(ProviderValidationException.class);
            assertThatThrownBy(() -> store.createProvider(new ProviderInput("n", "https://x/v1", "k", "")))
                    .isInstanceOf(ProviderValidationException.class);
        }
        assertThat(ProviderStore.maskApiKey("sk-test-key-1234")).isEqualTo("••••••••1234");
        assertThat(ProviderStore.maskApiKey("short")).isEqualTo("••••••••");
        assertThat(ProviderStore.maskApiKey("")).isEmpty();
    }
}
