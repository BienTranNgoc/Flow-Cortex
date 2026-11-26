package com.flowcortex.connector.application.google;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code flowcortex.connectors.google.*}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleProperties.class)
class GoogleConfig {
}
