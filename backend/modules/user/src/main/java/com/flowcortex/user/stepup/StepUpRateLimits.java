package com.flowcortex.user.stepup;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The step-up attempt limit per user on the shared registry. It is the only brute-force guard on the
 * password check, so it refuses attempts while the rate-limit store is down instead of admitting them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StepUpProperties.class)
class StepUpRateLimits {

    static final String STEP_UP_USER = "step-up-user";

    @Bean
    RateLimitDefinition stepUpUser(StepUpProperties properties) {
        return RateLimitDefinition.failClosed(STEP_UP_USER, properties.perUser().capacity(),
                properties.perUser().period());
    }
}
