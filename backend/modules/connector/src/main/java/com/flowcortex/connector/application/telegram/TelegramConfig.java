package com.flowcortex.connector.application.telegram;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code flowcortex.connectors.telegram.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelegramProperties.class)
class TelegramConfig {
}
