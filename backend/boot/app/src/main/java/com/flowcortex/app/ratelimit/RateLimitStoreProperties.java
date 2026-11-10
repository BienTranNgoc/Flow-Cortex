package com.flowcortex.app.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sizing of the limiter store's own connection pool. Small and fast to fail: a stalled store
 * must not hold a request for long (the limiter then admits), and must not drain the application pool.
 *
 * @param maxPoolSize       connections the limiter store may hold open
 * @param connectionTimeout how long a call waits for a free connection (Hikari's minimum is 250 ms)
 * @param lockTimeout       how long a call waits for another call's row lock on the same bucket
 */
@ConfigurationProperties("flowcortex.rate-limit.store")
record RateLimitStoreProperties(
        @DefaultValue("8") int maxPoolSize,
        @DefaultValue("250ms") Duration connectionTimeout,
        @DefaultValue("200ms") Duration lockTimeout) {
}
