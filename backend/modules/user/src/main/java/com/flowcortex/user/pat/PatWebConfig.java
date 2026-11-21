package com.flowcortex.user.pat;

import com.flowcortex.shared.ratelimit.RateLimiters;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Declared as beans, not scanned, so a controller slice test does not pull these in without their dependencies. */
@Configuration(proxyBeanMethods = false)
class PatWebConfig {

    @Bean
    PatRateLimitFilter patRateLimitFilter(RateLimiters rateLimiters,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        return new PatRateLimitFilter(rateLimiters, exceptionResolver);
    }

    @Bean
    PersonalAccessTokenSwitch personalAccessTokenSwitch(PersonalAccessTokenProperties properties) {
        return new PersonalAccessTokenSwitch(properties);
    }
}
