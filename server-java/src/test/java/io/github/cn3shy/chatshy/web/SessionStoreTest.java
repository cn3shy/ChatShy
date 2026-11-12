package io.github.cn3shy.chatshy.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionStoreTest {
    @Test
    void createVerifyDestroy() {
        SessionStore store = new SessionStore();
        String token = store.create();
        assertThat(store.verify(token)).isTrue();
        assertThat(store.verify("nope")).isFalse();
        store.destroy(token);
        assertThat(store.verify(token)).isFalse();
    }

    @Test
    void fiveFailuresLockTheIpUntilReset() {
        SessionStore store = new SessionStore();
        for (int i = 0; i < 4; i++) store.registerFailure("1.2.3.4");
        assertThat(store.isLocked("1.2.3.4")).isFalse();
        store.registerFailure("1.2.3.4");
        assertThat(store.isLocked("1.2.3.4")).isTrue();
        assertThat(store.isLocked("5.6.7.8")).isFalse();
        store.resetFailures("1.2.3.4");
        assertThat(store.isLocked("1.2.3.4")).isFalse();
    }
}
