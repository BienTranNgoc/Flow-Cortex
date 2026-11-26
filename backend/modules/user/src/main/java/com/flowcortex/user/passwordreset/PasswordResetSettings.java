package com.flowcortex.user.passwordreset;

import com.flowcortex.settings.api.SettingDefinition;
import com.flowcortex.settings.api.SettingKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The runtime switch over the two reset routes. The key is {@code pw_reset}, not {@code password_reset}:
 * the settings registry refuses any key segment that contains {@code password}.
 */
@Configuration(proxyBeanMethods = false)
class PasswordResetSettings {

    static final SettingKey<Boolean> ENABLED = SettingKey.switchKey("user.pw_reset.enabled");

    @Bean
    SettingDefinition<Boolean> passwordResetEnabledSetting(PasswordResetProperties properties) {
        return SettingDefinition.switchKey(ENABLED.name(), true, properties::enabled);
    }
}
