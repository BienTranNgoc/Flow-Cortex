package com.flowcortex.user.apicall;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Declared as a bean, not scanned, so a controller slice test does not pull the filter in without its stores. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiTokenCallsProperties.class)
class ApiTokenCallConfig {

    @Bean
    ApiTokenCallFilter apiTokenCallFilter(ApiTokenCallsProperties properties, JdbcApiTokenCallWriter calls,
            IdempotencyStore idempotency, Clock clock,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        return new ApiTokenCallFilter(properties, calls, idempotency, clock, exceptionResolver);
    }
}
