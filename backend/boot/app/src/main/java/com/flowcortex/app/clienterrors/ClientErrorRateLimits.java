package com.flowcortex.app.clienterrors;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientErrorsProperties.class)
class ClientErrorRateLimits {

    static final String CLIENT_ERRORS_IP = "client-errors-ip";

    @Bean
    RateLimitDefinition clientErrorsIpLimit(ClientErrorsProperties properties) {
        return new RateLimitDefinition(CLIENT_ERRORS_IP, properties.perIp().capacity(), properties.perIp().period());
    }
}
