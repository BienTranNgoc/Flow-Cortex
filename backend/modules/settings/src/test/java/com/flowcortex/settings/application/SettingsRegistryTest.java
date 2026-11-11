package com.flowcortex.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.settings.api.SettingDefinition;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SettingsRegistryTest {

    private static SettingDefinition<Boolean> aSwitch(String name) {
        return SettingDefinition.switchKey(name, true, () -> null);
    }

    private static SettingDefinition<Integer> anInteger(String name, int min, int max, int configured) {
        return SettingDefinition.integerKey(name, min, max, () -> configured);
    }

    @Test
    void aDuplicateKeyIsRefusedByName() {
        assertThatThrownBy(() -> new SettingsRegistry(List.of(
                aSwitch("support.assistant.enabled"), aSwitch("support.assistant.enabled"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("support.assistant.enabled")
                .hasMessageContaining("twice");
    }

    @Test
    void aSecretLikeSegmentIsRefused() {
        for (String key : List.of("llm.gemini.api_key", "x.y.token", "x.y.client_secret", "x.y.db_password",
                "x.y.apikey", "x.y.service_credential", "x.y.private_key")) {
            assertThatThrownBy(() -> new SettingsRegistry(List.of(aSwitch(key))))
                    .as(key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining("secret");
        }
    }

    @Test
    void aMalformedKeyIsRefused() {
        for (String key : List.of("Support.AssistantEnabled", "support.assistant", "support..x",
                "support.assistant.", ".support.assistant.x", "support.assistant.1x", "support.assistant.x__y",
                "support.assistant.x_")) {
            assertThatThrownBy(() -> new SettingsRegistry(List.of(aSwitch(key))))
                    .as(key)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key)
                    .hasMessageContaining("malformed");
        }
    }

    @Test
    void anIntegerKeyWithMinAboveMaxIsRefused() {
        assertThatThrownBy(() -> new SettingsRegistry(List.of(anInteger("support.assistant.limit", 10, 5, 7))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("support.assistant.limit")
                .hasMessageContaining("bounds");
    }

    @Test
    void aConfiguredValueOutsideTheBoundsIsRefusedByName() {
        AtomicInteger reads = new AtomicInteger();
        SettingDefinition<Integer> quota = SettingDefinition.integerKey("support.assistant.member_daily_quota", 1,
                Integer.MAX_VALUE, () -> {
                    reads.incrementAndGet();
                    return 0;
                });

        assertThatThrownBy(() -> new SettingsRegistry(List.of(quota)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("support.assistant.member_daily_quota")
                .hasMessageContaining("1")
                .hasMessageContaining("2147483647");
        assertThat(reads).hasValue(1);
    }

    @Test
    void anUnsetConfiguredIntegerIsRefusedByName() {
        SettingDefinition<Integer> quota = SettingDefinition.integerKey("support.assistant.member_daily_quota", 1,
                100, () -> null);

        assertThatThrownBy(() -> new SettingsRegistry(List.of(quota)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("support.assistant.member_daily_quota");
    }

    @Test
    void aConfiguredValueAtTheBoundsIsAccepted() {
        SettingsRegistry registry = new SettingsRegistry(List.of(
                anInteger("a.b.low", 1, 10, 1), anInteger("a.b.high", 1, 10, 10)));

        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void wellFormedKeysAreListedInKeyOrder() {
        SettingsRegistry registry = new SettingsRegistry(List.of(
                aSwitch("support.assistant.enabled"),
                anInteger("support.assistant.member_daily_quota", 1, 100, 5),
                anInteger("support.assistant.daily_ceiling", 1, 100, 5),
                aSwitch("llm.gemini.enabled")));

        assertThat(registry.all()).extracting(SettingDefinition::name).containsExactly(
                "llm.gemini.enabled",
                "support.assistant.daily_ceiling",
                "support.assistant.enabled",
                "support.assistant.member_daily_quota");
        assertThat(registry.definition("support.assistant.enabled")).isPresent();
        assertThat(registry.definition("support.assistant.colour")).isEmpty();
    }
}
