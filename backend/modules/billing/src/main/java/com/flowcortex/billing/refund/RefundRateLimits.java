package com.flowcortex.billing.refund;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The write budget of the refund routes: one per member, shared by every instance and by both the request and
 * the cancel route. A cancelled request holds no turn, so without it a request and cancel loop would write
 * unbounded rows and burn request codes. A constant, not a setting.
 */
@Configuration(proxyBeanMethods = false)
class RefundRateLimits {

    static final String NAME = "refund-request-member";
    static final long PER_MINUTE = 10;

    @Bean
    RateLimitDefinition refundRequestMemberLimit() {
        return new RateLimitDefinition(NAME, PER_MINUTE, Duration.ofMinutes(1));
    }
}
