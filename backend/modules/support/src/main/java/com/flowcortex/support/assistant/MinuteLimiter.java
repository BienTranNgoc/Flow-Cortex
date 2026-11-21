package com.flowcortex.support.assistant;

import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * At most ten questions a minute per asker and, for guests, per network. The budgets live in the
 * shared rate-limit store, so every backend instance draws from the same ones. Network and asker
 * limits are separate limiters: a flood of client-minted session keys cannot touch a network's budget,
 * and the network cap is what stops that flood.
 */
@Component
class MinuteLimiter {

    static final int PER_MINUTE = 10;

    private final RateLimiter askers;
    private final RateLimiter networks;
    private final Clock clock;

    MinuteLimiter(RateLimiters limiters, Clock clock) {
        this.askers = limiters.named(AssistantRateLimits.ASKER);
        this.networks = limiters.named(AssistantRateLimits.NETWORK);
        this.clock = clock;
    }

    /** Takes one question from the asker's budget, or refuses with 429 and the time of the next allowed one. */
    void acquire(String subjectKey) {
        take(askers, subjectKey);
    }

    /** Takes one question from the network's budget, or refuses like {@link #acquire}. */
    void acquireNetwork(String networkKey) {
        take(networks, networkKey);
    }

    private void take(RateLimiter limiter, String key) {
        limiter.tryAcquire(key).ifPresent(wait -> {
            throw QuotaService.exhausted(Instant.now(clock).plus(wait));
        });
    }
}
