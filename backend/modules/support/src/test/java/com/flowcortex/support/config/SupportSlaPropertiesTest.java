package com.flowcortex.support.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.settings.api.SettingDefinition;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SupportSlaPropertiesTest {

    private static SupportSlaProperties properties(int newHours, int newWarnHours, int days, int warnDays) {
        return new SupportSlaProperties(true, newHours, newWarnHours, days, warnDays, Duration.ofMinutes(5));
    }

    @Test
    void aWellOrderedRecordIsAccepted() {
        SupportSlaProperties accepted = properties(24, 20, 3, 2);

        assertThat(accepted.alertsEnabled()).isTrue();
        assertThat(accepted.newHours()).isEqualTo(24);
        assertThat(accepted.newWarnHours()).isEqualTo(20);
        assertThat(accepted.inProgressDays()).isEqualTo(3);
        assertThat(accepted.inProgressWarnDays()).isEqualTo(2);
        assertThat(accepted.sweepInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aNewWarningNotBelowItsLimitFailsNamingBothKeys() {
        assertThatThrownBy(() -> properties(24, 24, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("support.sla.new_warn_hours")
                .hasMessageContaining("support.sla.new_hours");
        assertThatThrownBy(() -> properties(24, 30, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("support.sla.new_warn_hours")
                .hasMessageContaining("support.sla.new_hours");
    }

    @Test
    void anInProgressWarningEqualToItsLimitFailsNamingBothKeys() {
        assertThatThrownBy(() -> properties(24, 20, 3, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("support.sla.in_progress_warn_days")
                .hasMessageContaining("support.sla.in_progress_days");
    }

    @Test
    void theSettingDefinitionsCarryTheBoundsOfTheRegistry() {
        SupportSlaSettings settings = new SupportSlaSettings();
        SupportSlaProperties configured = properties(24, 20, 3, 2);

        SettingDefinition<Boolean> alerts = settings.slaAlertsEnabledSetting(configured);
        assertThat(alerts.name()).isEqualTo("support.sla.alerts.enabled");
        assertThat(alerts.codeDefault()).isTrue();
        assertThat(alerts.configuredValue()).isTrue();

        assertInteger(settings.slaNewHoursSetting(configured), "support.sla.new_hours", 1, 720, 24);
        assertInteger(settings.slaNewWarnHoursSetting(configured), "support.sla.new_warn_hours", 1, 720, 20);
        assertInteger(settings.slaInProgressDaysSetting(configured), "support.sla.in_progress_days", 1, 90, 3);
        assertInteger(settings.slaInProgressWarnDaysSetting(configured), "support.sla.in_progress_warn_days", 1, 90,
                2);
    }

    @Test
    void aSwitchConfiguredOffReadsOffFromTheDefinition() {
        SupportSlaProperties off = new SupportSlaProperties(false, 24, 20, 3, 2, Duration.ofMinutes(5));

        assertThat(new SupportSlaSettings().slaAlertsEnabledSetting(off).configuredValue()).isFalse();
    }

    private static void assertInteger(SettingDefinition<Integer> definition, String name, int min, int max,
            int configured) {
        assertThat(definition.name()).isEqualTo(name);
        assertThat(definition.min()).isEqualTo(min);
        assertThat(definition.max()).isEqualTo(max);
        assertThat(definition.configuredValue()).isEqualTo(configured);
    }
}
