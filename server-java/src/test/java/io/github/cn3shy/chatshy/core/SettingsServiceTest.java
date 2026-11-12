package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.cn3shy.chatshy.TestDb;

class SettingsServiceTest {

    @Test
    void initialValuesAreSeededOnlyOnce() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            settings.initSettings(5000, "chatshy");
            settings.initSettings(9999, "other");
            assertThat(settings.getDefaultTimeoutMs()).isEqualTo(5000);
            assertThat(settings.getModelName()).isEqualTo("chatshy");

            settings.setSetting(SettingsService.DEFAULT_TIMEOUT_MS, "7000");
            assertThat(settings.getDefaultTimeoutMs()).isEqualTo(7000);
            settings.setSetting(SettingsService.DEFAULT_TIMEOUT_MS, "not-a-number");
            assertThat(settings.getDefaultTimeoutMs(1234)).isEqualTo(1234);
        }
    }

    @Test
    void adminPasswordBootstrapAndVerify() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            var first = settings.ensureAdminPassword("from-env");
            assertThat(first.source()).isEqualTo("env");
            assertThat(first.password()).isNull();
            assertThat(settings.verifyAdminPassword("from-env")).isTrue();

            var second = settings.ensureAdminPassword("another");
            assertThat(second.source()).isNull();
            assertThat(settings.verifyAdminPassword("another")).isFalse();
            assertThat(settings.verifyAdminPassword("from-env")).isTrue();
        }
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            var generated = settings.ensureAdminPassword(null);
            assertThat(generated.source()).isEqualTo("generated");
            assertThat(generated.password()).isNotNull();
            assertThat(settings.verifyAdminPassword(generated.password())).isTrue();
        }
    }

    @Test
    void callerKeyBootstrapAndRegenerate() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            assertThat(settings.getCallerKey()).isNull();

            var first = settings.ensureCallerKey("sk-chatshy-from-env");
            assertThat(first.source()).isEqualTo("env");
            assertThat(settings.getCallerKey()).isEqualTo("sk-chatshy-from-env");

            var second = settings.ensureCallerKey("other");
            assertThat(second.source()).isEqualTo("existing");
            assertThat(settings.getCallerKey()).isEqualTo("sk-chatshy-from-env");

            String fresh = settings.regenerateCallerKey();
            assertThat(fresh).startsWith("sk-chatshy-");
            assertThat(settings.getCallerKey()).isEqualTo(fresh);
            assertThat(settings.callerKeyMatches(fresh, "sk-chatshy-from-env")).isFalse();
            assertThat(settings.callerKeyMatches(fresh, fresh)).isTrue();
            assertThat(settings.callerKeyMatches(null, fresh)).isFalse();
        }
    }

    @Test
    void autoForwardSettingsPersist() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            assertThat(settings.isAutoForwardEnabled()).isFalse();
            assertThat(settings.getAutoForwardProviderId()).isEmpty();
            settings.setAutoForwardEnabled(true);
            settings.setAutoForwardProviderId("provider-1");
            assertThat(settings.isAutoForwardEnabled()).isTrue();
            assertThat(settings.getAutoForwardProviderId()).isEqualTo("provider-1");
        }
    }
}
