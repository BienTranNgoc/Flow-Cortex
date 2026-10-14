package com.flowcortex.user.application;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the {@code @ConfigurationProperties} records that the user module
 * declares. Without this, the records are parsed but not bound.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AccessTokenProperties.class, RefreshTokenProperties.class, GoogleOAuthProperties.class,
        LoginThrottleProperties.class, AuthRetentionProperties.class})
public class UserModuleConfig {
}
