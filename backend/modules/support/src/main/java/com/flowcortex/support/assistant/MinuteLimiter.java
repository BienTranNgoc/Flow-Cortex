package com.flowcortex.support.assistant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * At most ten questions a minute per asker, as an in-memory token bucket refilled gradually. Per
 * instance: with N backend replicas an asker could reach N times the rate; the daily quotas stay exact.
 */
@Component
class MinuteLimiter {

    static final int PER_MINUTE = 10;

    private static final Duration PERIOD = Duration.ofMinutes(1);
    private static final long MAX_TRACKED_KEYS = 100_000;

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(PERIOD.multipliedBy(2))
            .maximumSize(MAX_TRACKED_KEYS)
            .build();
    private final Clock clock;

    MinuteLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Takes one question from the asker's bucket, or refuses with 429 and the time of the next allowed one. */
    void acquire(String subjectKey) {
        Bucket bucket = buckets.get(subjectKey, ignored -> Bucket.builder()
                .addLimit(limit -> limit.capacity(PER_MINUTE).refillGreedy(PER_MINUTE, PERIOD))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw QuotaService.exhausted(Instant.now(clock).plusNanos(probe.getNanosToWaitForRefill()));
        }
    }
}
