package com.flowcortex.user.pat;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The per-token request limits on the shared registry; also registers the token settings the pat classes read. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PatRateLimitProperties.class, PersonalAccessTokenProperties.class})
class PatRateLimits {

    static final String PAT_TOKEN_MINUTE = "pat-token-minute";
    static final String PAT_TOKEN_DAY = "pat-token-day";

    @Bean
    RateLimitDefinition patTokenMinute(PatRateLimitProperties properties) {
        return new RateLimitDefinition(PAT_TOKEN_MINUTE, properties.perMinute().capacity(),
                properties.perMinute().period());
    }

    @Bean
    RateLimitDefinition patTokenDay(PatRateLimitProperties properties) {
        return new RateLimitDefinition(PAT_TOKEN_DAY, properties.perDay().capacity(), properties.perDay().period());
    }
}
