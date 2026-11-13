package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.ProviderClient.TokenUsage;

class ProviderClientUsageTest {

    @Test
    void parsesOpenAiStandardUsage() throws Exception {
        JsonNode data = Json.parse("""
                {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":50,
                 "prompt_tokens_details":{"cached_tokens":40}}
                }
                """);
        TokenUsage usage = ProviderClient.usageFromJson(data);
        assertThat(usage.inputTokens()).isEqualTo(100);
        assertThat(usage.outputTokens()).isEqualTo(50);
        assertThat(usage.cacheReadTokens()).isEqualTo(40);
        assertThat(usage.known()).isTrue();
    }

    @Test
    void parsesDeepSeekCacheField() throws Exception {
        JsonNode data = Json.parse("""
                {"usage":{"prompt_tokens":200,"completion_tokens":10,
                 "prompt_cache_hit_tokens":120,"prompt_cache_miss_tokens":80}}
                """);
        TokenUsage usage = ProviderClient.usageFromJson(data);
        assertThat(usage.cacheReadTokens()).isEqualTo(120);
    }

    @Test
    void parsesAnthropicStyleFieldNames() throws Exception {
        // 有些兼容端点用 input_tokens / output_tokens / cache_read_input_tokens
        JsonNode data = Json.parse("""
                {"usage":{"input_tokens":7,"output_tokens":3,"cache_read_input_tokens":5}}
                """);
        TokenUsage usage = ProviderClient.usageFromJson(data);
        assertThat(usage.inputTokens()).isEqualTo(7);
        assertThat(usage.outputTokens()).isEqualTo(3);
        assertThat(usage.cacheReadTokens()).isEqualTo(5);
    }

    @Test
    void missingUsageYieldsUnknown() throws Exception {
        assertThat(ProviderClient.usageFromJson(Json.parse("{\"choices\":[]}")).known()).isFalse();
        assertThat(ProviderClient.usageFromJson(null).known()).isFalse();
        assertThat(ProviderClient.usageFromJson(Json.parse("\"just a string\"")).known()).isFalse();
        // usage 对象存在但字段缺失：input/output 未知，known 仍为 false
        assertThat(ProviderClient.usageFromJson(Json.parse("{\"usage\":{}}")).known()).isFalse();
    }

    @Test
    void partialUsageIsKnown() throws Exception {
        JsonNode data = Json.parse("{\"usage\":{\"prompt_tokens\":42}}");
        TokenUsage usage = ProviderClient.usageFromJson(data);
        assertThat(usage.known()).isTrue();
        assertThat(usage.inputTokens()).isEqualTo(42);
        assertThat(usage.outputTokens()).isEqualTo(-1);
        assertThat(usage.cacheReadTokens()).isEqualTo(-1);
    }
}
