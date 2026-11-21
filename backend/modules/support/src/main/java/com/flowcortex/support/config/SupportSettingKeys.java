package com.flowcortex.support.config;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Help Center's runtime settings. The configured layer reads {@link SupportProperties} on every
 * call; the integers are bounded exactly as the properties validate them.
 */
@Configuration(proxyBeanMethods = false)
public class SupportSettingKeys {

    public static final SettingKey<Boolean> ASSISTANT_ENABLED = SettingKey.switchKey("support.assistant.enabled");
    public static final SettingKey<Integer> DAILY_CEILING = SettingKey.integerKey("support.assistant.daily_ceiling");
    public static final SettingKey<Integer> MEMBER_DAILY_QUOTA =
            SettingKey.integerKey("support.assistant.member_daily_quota");
    public static final SettingKey<Integer> GUEST_DAILY_QUOTA =
            SettingKey.integerKey("support.assistant.guest_daily_quota");
    public static final SettingKey<Integer> GUEST_NETWORK_DAILY_QUOTA =
            SettingKey.integerKey("support.assistant.guest_network_daily_quota");

    @Bean
    SettingDefinition<Boolean> assistantEnabledSetting(SupportProperties properties) {
        return SettingDefinition.switchKey(ASSISTANT_ENABLED.name(), true, () -> properties.assistant().enabled());
    }

    @Bean
    SettingDefinition<Integer> dailyCeilingSetting(SupportProperties properties) {
        return limit(DAILY_CEILING, () -> properties.assistant().dailyCeiling());
    }

    @Bean
    SettingDefinition<Integer> memberDailyQuotaSetting(SupportProperties properties) {
        return limit(MEMBER_DAILY_QUOTA, () -> properties.assistant().memberDailyQuota());
    }

    @Bean
    SettingDefinition<Integer> guestDailyQuotaSetting(SupportProperties properties) {
        return limit(GUEST_DAILY_QUOTA, () -> properties.assistant().guestDailyQuota());
    }

    @Bean
    SettingDefinition<Integer> guestNetworkDailyQuotaSetting(SupportProperties properties) {
        return limit(GUEST_NETWORK_DAILY_QUOTA, () -> properties.assistant().guestNetworkDailyQuota());
    }

    private static SettingDefinition<Integer> limit(SettingKey<Integer> key, Supplier<Integer> configured) {
        return SettingDefinition.integerKey(key.name(), 1, Integer.MAX_VALUE, configured);
    }
}
