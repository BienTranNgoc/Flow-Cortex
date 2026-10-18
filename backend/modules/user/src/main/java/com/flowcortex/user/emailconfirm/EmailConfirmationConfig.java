package com.flowcortex.user.emailconfirm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailConfirmationProperties.class)
class EmailConfirmationConfig {
}
