package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import io.github.cn3shy.chatshy.core.SourceService.RequestSource;

class SourceServiceTest {

    @Test
    void detectClientCommonClients() {
        assertThat(SourceService.detectClient("claude-cli/2.0.24 (external, cli)")).isEqualTo("Claude Code");
        assertThat(SourceService.detectClient("CherryStudio/1.6.0")).isEqualTo("Cherry Studio");
        assertThat(SourceService.detectClient("Chatbox/1.9.0")).isEqualTo("ChatBox");
        assertThat(SourceService.detectClient("OpenAI/Python 1.55.0")).isEqualTo("OpenAI Python SDK");
        assertThat(SourceService.detectClient("OpenAI/NodeJS/4.70.0")).isEqualTo("OpenAI Node SDK");
        assertThat(SourceService.detectClient("Anthropic/Python 0.40.0")).isEqualTo("Anthropic Python SDK");
        assertThat(SourceService.detectClient("curl/8.4.0")).isEqualTo("curl");
        assertThat(SourceService.detectClient("python-requests/2.31.0")).isEqualTo("Python 脚本");
        assertThat(SourceService.detectClient("axios/1.6.0")).isEqualTo("脚本客户端");
        assertThat(SourceService.detectClient(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"))
                .isEqualTo("浏览器");
    }

    @Test
    void detectClientUnknownAndEmpty() {
        assertThat(SourceService.detectClient("")).isEqualTo("未知来源");
        assertThat(SourceService.detectClient("   ")).isEqualTo("未知来源");
        assertThat(SourceService.detectClient("SomeVendorTool/3.1")).isEqualTo("其他（SomeVendorTool/3.1）");
        assertThat(SourceService.detectClient("X".repeat(100))).isEqualTo("其他（" + "X".repeat(39) + "…）");
    }

    @Test
    void normalizeIpMappedIpv4() {
        assertThat(HttpUtil.normalizeIp("::ffff:127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(HttpUtil.normalizeIp("::FFFF:10.0.0.7")).isEqualTo("10.0.0.7");
        assertThat(HttpUtil.normalizeIp("2001:db8::1")).isEqualTo("2001:db8::1");
        assertThat(HttpUtil.normalizeIp(" 1.2.3.4 ")).isEqualTo("1.2.3.4");
    }

    @Test
    void fingerprintMatchesNode() {
        // 与 Node 版 crypto.createHash('sha256') 逐字节一致
        assertThat(SourceService.fingerprintOf("1.2.3.4", "claude-cli/2.0.24 (external, cli)", "Claude Code"))
                .isEqualTo("0a1fd47992f3a814335235dbaa918027");
        assertThat(SourceService.fingerprintOf("203.0.113.7", "curl/8.4.0", "张三", "张三"))
                .isEqualTo("1ae6a7745af9d2eac387778be10bbf87");
        assertThat(SourceService.fingerprintOf("1.2.3.4", "Mozilla/5.0 Chrome/120", "浏览器"))
                .isEqualTo("cf15d8a41987f61598b5d3d9c3261d39");
    }

    @Test
    void fingerprintClientVersionDoesNotSplitThread() {
        String before = SourceService.fingerprintOf("1.2.3.4", "CherryStudio/1.5.0", "Cherry Studio");
        String after = SourceService.fingerprintOf("1.2.3.4", "CherryStudio/1.9.3", "Cherry Studio");
        assertThat(before).isEqualTo(after);
    }

    @Test
    void parseCaller() {
        assertThat(SourceService.parseCaller("  张 \n 三 \r\n")).isEqualTo("张 三");
        assertThat(SourceService.parseCaller("x".repeat(100))).hasSize(40);
        assertThat(SourceService.parseCaller("🙂".repeat(60))).isEqualTo("🙂".repeat(40));
        assertThat(SourceService.parseCaller(null)).isEmpty();
        assertThat(SourceService.parseCaller("   ")).isEmpty();
        assertThat(SourceService.parseCaller("%E5%BC%A0%E4%B8%89")).isEqualTo("张三");
        String rawBytes = new String("张三".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        assertThat(SourceService.parseCaller(rawBytes)).isEqualTo("张三");
        assertThat(SourceService.parseCaller("50%")).isEqualTo("50%");
    }

    @Test
    void requestSourceUsesCallerHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "CherryStudio/1.6.0");
        request.addHeader("x-chatshy-caller", "%E5%BC%A0%E4%B8%89");
        RequestSource source = SourceService.requestSource(request);
        assertThat(source.client()).isEqualTo("张三");
        assertThat(source.caller()).isEqualTo("张三");
        assertThat(source.ua()).isEqualTo("CherryStudio/1.6.0");
        assertThat(source.ip()).isEqualTo("127.0.0.1");
        assertThat(source.fingerprint()).hasSize(32);
    }

    @Test
    void clientIpTrustsForwardedHeadersOnlyFromPrivatePeer() {
        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.setRemoteAddr("127.0.0.1");
        proxied.addHeader("X-Real-IP", "203.0.113.9");
        assertThat(HttpUtil.clientIp(proxied)).isEqualTo("203.0.113.9");

        MockHttpServletRequest xff = new MockHttpServletRequest();
        xff.setRemoteAddr("172.18.0.3");
        xff.addHeader("X-Forwarded-For", "203.0.113.10, 172.18.0.3");
        assertThat(HttpUtil.clientIp(xff)).isEqualTo("203.0.113.10");

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.4");
        direct.addHeader("X-Real-IP", "10.0.0.1");
        assertThat(HttpUtil.clientIp(direct)).isEqualTo("198.51.100.4");
    }
}
