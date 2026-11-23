package com.flowcortex.user.oauth;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The per-address limits of the open endpoints, on the shared registry so every instance counts together. */
@Configuration(proxyBeanMethods = false)
class OAuthRateLimits {

    static final String REGISTER_IP = "oauth-register-ip";
    static final String TOKEN_IP = "oauth-token-ip";

    @Bean
    RateLimitDefinition oauthRegisterIp(McpOAuthProperties properties) {
        return new RateLimitDefinition(REGISTER_IP, properties.registerPerIp().capacity(),
                properties.registerPerIp().period());
    }

    @Bean
    RateLimitDefinition oauthTokenIp(McpOAuthProperties properties) {
        return new RateLimitDefinition(TOKEN_IP, properties.tokenPerIp().capacity(), properties.tokenPerIp().period());
    }
}
