package com.flowcortex.billing.journey;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The journey route's limit: one budget per member, shared by every instance. One journey produces at most
 * three observations, so 30 a minute leaves room for retries from two tabs. A constant, not a setting.
 */
@Configuration(proxyBeanMethods = false)
class JourneyRateLimits {

    static final String NAME = "payment-journey-member";
    static final long PER_MINUTE = 30;

    @Bean
    RateLimitDefinition paymentJourneyMemberLimit() {
        return new RateLimitDefinition(NAME, PER_MINUTE, Duration.ofMinutes(1));
    }
}
