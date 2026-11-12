package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordsTest {
    /** 由 Node crypto.scryptSync(password, salt, 64, {N:16384,r:8,p:1}) 生成，验证跨版本兼容。 */
    private static final String NODE_HASH = "scrypt$16384$8$1$000102030405060708090a0b0c0d0e0f$"
            + "bc8e1c4b7c9313d6478723a96a7b6f3f2de9f5e1e7a3c693f633eb5cfbe295d4"
            + "42c014a6f045f66c72b558f27ab6d266491d2fea3e3c44048dc68b46e17e182d";

    @Test
    void verifiesNodeGeneratedScryptHash() {
        assertThat(Passwords.verifyPassword("hello-world", NODE_HASH)).isTrue();
        assertThat(Passwords.verifyPassword("wrong", NODE_HASH)).isFalse();
        assertThat(Passwords.verifyPassword("hello-world", "garbage")).isFalse();
        assertThat(Passwords.verifyPassword("hello-world", "scrypt$1$2$3$zz$")).isFalse();
    }

    @Test
    void hashRoundTrip() {
        String hash = Passwords.hashPassword("hello-world");
        assertThat(hash).startsWith("scrypt$16384$8$1$");
        assertThat(Passwords.verifyPassword("hello-world", hash)).isTrue();
        assertThat(Passwords.verifyPassword("hello-world!", hash)).isFalse();
    }

    @Test
    void keysAndConstantTimeCompare() {
        assertThat(Passwords.newCallerKey()).matches("^sk-chatshy-[A-Za-z0-9_-]{43}$");
        assertThat(Passwords.newAdminPassword()).hasSize(16);
        assertThat(Passwords.constantTimeEquals("sk-chatshy-abc", "sk-chatshy-abc")).isTrue();
        assertThat(Passwords.constantTimeEquals("sk-chatshy-abc", "sk-chatshy-abcdefghij")).isFalse();
        assertThat(Passwords.constantTimeEquals("", "x")).isFalse();
    }
}
