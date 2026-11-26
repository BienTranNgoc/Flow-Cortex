package com.flowcortex.support.config;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The runtime settings behind a request's SLA level and its alerts. The configured layer reads
 * {@link SupportSlaProperties} on every call; the bounds are those the properties validate.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupportSlaProperties.class)
public class SupportSlaSettings {

    public static final SettingKey<Boolean> ALERTS_ENABLED = SettingKey.switchKey("support.sla.alerts.enabled");
    public static final SettingKey<Integer> NEW_HOURS = SettingKey.integerKey("support.sla.new_hours");
    public static final SettingKey<Integer> NEW_WARN_HOURS = SettingKey.integerKey("support.sla.new_warn_hours");
    public static final SettingKey<Integer> IN_PROGRESS_DAYS = SettingKey.integerKey("support.sla.in_progress_days");
    public static final SettingKey<Integer> IN_PROGRESS_WARN_DAYS =
            SettingKey.integerKey("support.sla.in_progress_warn_days");

    @Bean
    SettingDefinition<Boolean> slaAlertsEnabledSetting(SupportSlaProperties properties) {
        return SettingDefinition.switchKey(ALERTS_ENABLED.name(), true, properties::alertsEnabled);
    }

    @Bean
    SettingDefinition<Integer> slaNewHoursSetting(SupportSlaProperties properties) {
        return SettingDefinition.integerKey(NEW_HOURS.name(), 1, 720, properties::newHours);
    }

    @Bean
    SettingDefinition<Integer> slaNewWarnHoursSetting(SupportSlaProperties properties) {
        return SettingDefinition.integerKey(NEW_WARN_HOURS.name(), 1, 720, properties::newWarnHours);
    }

    @Bean
    SettingDefinition<Integer> slaInProgressDaysSetting(SupportSlaProperties properties) {
        return SettingDefinition.integerKey(IN_PROGRESS_DAYS.name(), 1, 90, properties::inProgressDays);
    }

    @Bean
    SettingDefinition<Integer> slaInProgressWarnDaysSetting(SupportSlaProperties properties) {
        return SettingDefinition.integerKey(IN_PROGRESS_WARN_DAYS.name(), 1, 90, properties::inProgressWarnDays);
    }
}
