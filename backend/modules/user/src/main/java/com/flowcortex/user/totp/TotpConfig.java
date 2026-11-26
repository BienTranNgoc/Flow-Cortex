package com.flowcortex.user.totp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code flowcortex.auth.totp.*} and builds the keyring cipher, so a missing or malformed key stops
 * startup. The requirement is a bean of its own so a test can replace it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TotpProperties.class)
class TotpConfig {

    @Bean
    TotpSecretCipher totpSecretCipher(TotpProperties properties) {
        return new TotpSecretCipher(properties);
    }

    @Bean
    TotpRequirement totpRequirement(TotpProperties properties) {
        return new TotpRequirement.Configured(properties);
    }
}
