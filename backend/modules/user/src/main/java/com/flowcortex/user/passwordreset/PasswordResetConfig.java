package com.flowcortex.user.passwordreset;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasswordResetProperties.class)
class PasswordResetConfig {
}
