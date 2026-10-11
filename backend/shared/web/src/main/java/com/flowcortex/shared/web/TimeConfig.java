package com.flowcortex.shared.web;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Project-wide {@link Clock} bean. Tests inject a fixed clock; production
 * uses UTC. Every code path that needs the current time uses
 * {@code Instant.now(clock)} (personal.md Appendix A.3) so a test can
 * freeze time without sleeping.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
