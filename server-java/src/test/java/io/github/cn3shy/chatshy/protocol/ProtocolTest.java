package io.github.cn3shy.chatshy.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.cn3shy.chatshy.core.Json;

class ProtocolTest {

    @Test
    void parseOpenAiStandardRequest() throws Exception {
        JsonNode body = Json.parse("""
                {"model":"gpt-x","temperature":0.7,"n":3,
                 "messages":[
                   {"role":"system","content":"你是助手"},
                   {"role":"user","content":"你好"},
                   {"role":"assistant","content":"你好呀"},
                   {"role":"user","content":"继续"}]}
                """);
        var parsed = OpenAiProtocol.parse(body);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.value().prompt().system()).isEqualTo("你是助手");
        assertThat(parsed.value().prompt().model()).isEqualTo("gpt-x");
        assertThat(parsed.value().prompt().messages()).containsExactly(
                new ProtocolTypes.ChatMessage("user", "你好"),
                new ProtocolTypes.ChatMessage("assistant", "你好呀"),
                new ProtocolTypes.ChatMessage("user", "继续"));
        assertThat(parsed.value().stream()).isFalse();
        assertThat(parsed.value().includeUsage()).isFalse();
    }

    @Test
    void parseOpenAiContentPartsAndToolCalls() throws Exception {
        JsonNode parts = Json.parse("""
                {"messages":[{"role":"user","content":[
                  {"type":"text","text":"看这张图"},
                  {"type":"image_url","image_url":{"url":"http://x/y.png"}},
                  {"type":"text","text":"谢谢"}]}]}
                """);
        var parsed = OpenAiProtocol.parse(parts);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.value().prompt().messages().get(0).content())
                .isEqualTo("看这张图[不支持的部件: image_url]谢谢");

        JsonNode tools = Json.parse("""
                {"messages":[{"role":"user","content":"查天气"},
                  {"role":"assistant","content":null,"tool_calls":[{"id":"c1"}]},
                  {"role":"tool","content":"上海 26 度"}]}
                """);
        var toolParsed = OpenAiProtocol.parse(tools);
        assertThat(toolParsed.ok()).isTrue();
        assertThat(toolParsed.value().prompt().messages()).containsExactly(
                new ProtocolTypes.ChatMessage("user", "查天气"),
                new ProtocolTypes.ChatMessage("assistant", "[不支持的部件: tool_calls]"),
                new ProtocolTypes.ChatMessage("user", "上海 26 度"));
    }

    @Test
    void parseOpenAiInvalid() throws Exception {
        assertThat(OpenAiProtocol.parse(null).ok()).isFalse();
        assertThat(OpenAiProtocol.parse(Json.parse("\"x\"")).ok()).isFalse();
        assertThat(OpenAiProtocol.parse(Json.parse("{}")).ok()).isFalse();
        assertThat(OpenAiProtocol.parse(Json.parse("{\"messages\":[]}")).ok()).isFalse();
        assertThat(OpenAiProtocol.parse(Json.parse("{\"messages\":[{\"role\":\"hacker\",\"content\":\"x\"}]}")).ok())
                .isFalse();
    }

    @Test
    void renderOpenAiCompletionShape() throws Exception {
        var params = new OpenAiProtocol.CompletionParams(
                "chatcmpl-test", 1_730_000_000L, "gpt-x", "你好，我是人工回复", 12, 34, false);
        JsonNode actual = Json.MAPPER.valueToTree(OpenAiProtocol.renderCompletion(params));
        JsonNode expected = Json.parse("""
                {"id":"chatcmpl-test","object":"chat.completion","created":1730000000,"model":"gpt-x",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"你好，我是人工回复"},
                             "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":34,"total_tokens":46}}
                """);
        // Jackson 的 IntNode 与 LongNode equals 不互认，按序列化后的文本比较
        assertThat(actual.toString()).isEqualTo(expected.toString());
    }

    @Test
    void openAiStreamFrames() throws Exception {
        var params = new OpenAiProtocol.CompletionParams(
                "chatcmpl-test", 1_730_000_000L, "gpt-x", "回复全文", 12, 34, true);
        List<String> frames = OpenAiProtocol.completionFinishFrames(params);
        assertThat(frames).hasSize(3);
        assertThat(frames.getLast()).isEqualTo("data: [DONE]\n\n");

        JsonNode finish = Json.parse(frames.get(0).substring("data: ".length()).trim());
        assertThat(finish.path("choices").get(0).path("finish_reason").asText()).isEqualTo("stop");
        JsonNode usage = Json.parse(frames.get(1).substring("data: ".length()).trim());
        assertThat(usage.path("usage").path("total_tokens").asInt()).isEqualTo(46);

        assertThat(OpenAiProtocol.newCompletionId()).matches("^chatcmpl-[0-9a-f]{24}$");
    }

    @Test
    void parseAnthropicRequest() throws Exception {
        JsonNode body = Json.parse("""
                {"model":"claude-x","max_tokens":1024,"system":"你是助手",
                 "messages":[{"role":"user","content":"你好"}]}
                """);
        var parsed = AnthropicProtocol.parse(body);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.value().prompt().system()).isEqualTo("你是助手");
        assertThat(parsed.value().prompt().messages()).containsExactly(
                new ProtocolTypes.ChatMessage("user", "你好"));

        JsonNode blocks = Json.parse("""
                {"system":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}],
                 "messages":[{"role":"user","content":[
                   {"type":"text","text":"看看"},
                   {"type":"image","source":{"type":"base64"}},
                   {"type":"tool_result","tool_use_id":"t1","content":"结果"}]}]}
                """);
        var blockParsed = AnthropicProtocol.parse(blocks);
        assertThat(blockParsed.ok()).isTrue();
        assertThat(blockParsed.value().prompt().system()).isEqualTo("第一段第二段");
        assertThat(blockParsed.value().prompt().messages().get(0).content())
                .isEqualTo("看看[不支持的块类型: image][不支持的块类型: tool_result]");
    }

    @Test
    void parseAnthropicInlineSystem() throws Exception {
        JsonNode body = Json.parse("""
                {"system":"顶层提示","messages":[
                  {"role":"system","content":"夹带提示一"},
                  {"role":"user","content":"你好"},
                  {"role":"system","content":[{"type":"text","text":"夹带提示二"}]}]}
                """);
        var parsed = AnthropicProtocol.parse(body);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.value().prompt().system()).isEqualTo("顶层提示\n\n夹带提示一\n\n夹带提示二");
        assertThat(parsed.value().prompt().messages()).containsExactly(
                new ProtocolTypes.ChatMessage("user", "你好"));
    }

    @Test
    void renderAnthropicMessageAndFrames() throws Exception {
        var params = new AnthropicProtocol.MessageParams("msg_test", "claude-x", "人工回复内容", 12, 34);
        JsonNode actual = Json.MAPPER.valueToTree(AnthropicProtocol.renderMessage(params));
        JsonNode expected = Json.parse("""
                {"id":"msg_test","type":"message","role":"assistant","model":"claude-x",
                 "content":[{"type":"text","text":"人工回复内容"}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":12,"output_tokens":34,
                          "cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
                """);
        assertThat(actual).isEqualTo(expected);

        List<String> frames = AnthropicProtocol.messageFinishFrames(params);
        assertThat(frames).hasSize(3);
        assertThat(frames.get(0)).startsWith("event: content_block_stop");
        assertThat(frames.get(1)).startsWith("event: message_delta");
        assertThat(frames.get(2)).startsWith("event: message_stop");
        assertThat(AnthropicProtocol.newMessageId()).matches("^msg_[0-9a-f]{24}$");
    }

    @Test
    void estimateTokens() {
        assertThat(ProtocolTypes.estimateTokens("")).isZero();
        assertThat(ProtocolTypes.estimateTokens("你好")).isEqualTo(2);
        assertThat(ProtocolTypes.estimateTokens("abcd")).isEqualTo(1);
        assertThat(ProtocolTypes.estimateTokens("abcde")).isEqualTo(2);
        assertThat(ProtocolTypes.estimateTokens("你好abcd")).isEqualTo(3);
    }
}
