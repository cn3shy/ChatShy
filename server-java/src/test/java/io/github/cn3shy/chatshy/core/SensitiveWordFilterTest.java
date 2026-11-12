package io.github.cn3shy.chatshy.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.cn3shy.chatshy.TestDb;

class SensitiveWordFilterTest {

    @Test
    void normalizesSpacesAndFullWidthCharacters() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            settings.setSetting(SettingsService.SENSITIVE_WORDS, "傻逼\nshit");
            SensitiveWordFilter filter = new SensitiveWordFilter(settings);

            assertThat(filter.match("傻 逼")).isEqualTo("傻逼");
            assertThat(filter.match("傻　逼")).isEqualTo("傻逼");
            assertThat(filter.match("ＳＨＩＴ")).isEqualTo("shit");
        }
    }

    @Test
    void englishWordsRespectBoundariesButCatchSpacedEvasion() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            settings.setSetting(SettingsService.SENSITIVE_WORDS, "ass\nfuck");
            SensitiveWordFilter filter = new SensitiveWordFilter(settings);

            assertThat(filter.match("class assignment")).isNull();
            assertThat(filter.match("you are an ass")).isEqualTo("ass");
            assertThat(filter.match("f u c k")).isEqualTo("fuck");
        }
    }

    @Test
    void usesBuiltInDefaultsWhenSettingIsEmpty() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            SensitiveWordFilter filter = new SensitiveWordFilter(settings);

            assertThat(filter.match("你个傻逼")).isEqualTo("傻逼");
        }
    }

    @Test
    void canBeDisabled() throws Exception {
        try (TestDb db = new TestDb()) {
            SettingsService settings = new SettingsService(db.jdbc());
            settings.setSetting(SettingsService.SENSITIVE_WORDS, "傻逼");
            settings.setSetting(SettingsService.SENSITIVE_FILTER_ENABLED, "0");
            SensitiveWordFilter filter = new SensitiveWordFilter(settings);

            assertThat(filter.match("傻逼")).isNull();
        }
    }
}
