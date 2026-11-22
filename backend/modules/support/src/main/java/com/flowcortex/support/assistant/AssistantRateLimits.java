package com.flowcortex.support.assistant;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The assistant's per-minute limits: one per asker and one per network, both shared by every instance. */
@Configuration(proxyBeanMethods = false)
class AssistantRateLimits {

    static final String ASKER = "assistant-asker";
    static final String NETWORK = "assistant-network";

    private static final Duration PERIOD = Duration.ofMinutes(1);

    @Bean
    RateLimitDefinition assistantAskerLimit() {
        return new RateLimitDefinition(ASKER, MinuteLimiter.PER_MINUTE, PERIOD);
    }

    @Bean
    RateLimitDefinition assistantNetworkLimit() {
        return new RateLimitDefinition(NETWORK, MinuteLimiter.PER_MINUTE, PERIOD);
    }
}
