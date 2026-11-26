package com.flowcortex.user.accountdeletion;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The account deletion runtime settings. Nothing erases data at the deadline yet, so the switch is
 * off until an operator turns it on; the grace period is frozen into each request when it is made.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountDeletionProperties.class)
public class AccountDeletionSettingKeys {

    public static final SettingKey<Boolean> ENABLED = SettingKey.switchKey("user.account_deletion.enabled");
    public static final SettingKey<Integer> GRACE_PERIOD_DAYS =
            SettingKey.integerKey("user.account_deletion.grace_period_days");

    @Bean
    SettingDefinition<Boolean> accountDeletionEnabledSetting(AccountDeletionProperties properties) {
        return SettingDefinition.switchKey(ENABLED.name(), false, properties::enabled);
    }

    @Bean
    SettingDefinition<Integer> accountDeletionGracePeriodDaysSetting(AccountDeletionProperties properties) {
        return SettingDefinition.integerKey(GRACE_PERIOD_DAYS.name(), 1, 365, properties::gracePeriodDays);
    }
}
