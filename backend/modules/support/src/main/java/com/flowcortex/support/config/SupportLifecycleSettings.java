package com.flowcortex.support.config;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The runtime settings that govern a request's lifecycle: auto-close, the reply mail and the purge of old
 * closed requests. The configured layer reads {@link SupportProperties#lifecycle()} on every call; the bounds
 * are those the properties validate.
 */
@Configuration(proxyBeanMethods = false)
public class SupportLifecycleSettings {

    public static final SettingKey<Boolean> AUTO_CLOSE_ENABLED = SettingKey.switchKey("support.auto_close.enabled");
    public static final SettingKey<Integer> AUTO_CLOSE_RESOLVED_DAYS =
            SettingKey.integerKey("support.auto_close.resolved_days");
    public static final SettingKey<Boolean> REPLY_MAIL_ENABLED = SettingKey.switchKey("support.reply_mail.enabled");
    public static final SettingKey<Integer> REPLY_MAIL_WINDOW_MINUTES =
            SettingKey.integerKey("support.reply_mail.window_minutes");
    public static final SettingKey<Boolean> RETENTION_ENABLED = SettingKey.switchKey("support.retention.enabled");
    public static final SettingKey<Integer> RETENTION_CLOSED_REQUEST_MONTHS =
            SettingKey.integerKey("support.retention.closed_request_months");

    @Bean
    SettingDefinition<Boolean> autoCloseEnabledSetting(SupportProperties properties) {
        return SettingDefinition.switchKey(AUTO_CLOSE_ENABLED.name(), true,
                () -> properties.lifecycle().autoCloseEnabled());
    }

    @Bean
    SettingDefinition<Integer> autoCloseResolvedDaysSetting(SupportProperties properties) {
        return SettingDefinition.integerKey(AUTO_CLOSE_RESOLVED_DAYS.name(), 1, 90,
                () -> properties.lifecycle().autoCloseResolvedDays());
    }

    @Bean
    SettingDefinition<Boolean> replyMailEnabledSetting(SupportProperties properties) {
        return SettingDefinition.switchKey(REPLY_MAIL_ENABLED.name(), true,
                () -> properties.lifecycle().replyMailEnabled());
    }

    @Bean
    SettingDefinition<Integer> replyMailWindowMinutesSetting(SupportProperties properties) {
        return SettingDefinition.integerKey(REPLY_MAIL_WINDOW_MINUTES.name(), 0, 1440,
                () -> properties.lifecycle().replyMailWindowMinutes());
    }

    @Bean
    SettingDefinition<Boolean> retentionEnabledSetting(SupportProperties properties) {
        return SettingDefinition.switchKey(RETENTION_ENABLED.name(), true,
                () -> properties.lifecycle().retentionEnabled());
    }

    @Bean
    SettingDefinition<Integer> retentionClosedRequestMonthsSetting(SupportProperties properties) {
        return SettingDefinition.integerKey(RETENTION_CLOSED_REQUEST_MONTHS.name(), 6, 120,
                () -> properties.lifecycle().retentionClosedRequestMonths());
    }
}
