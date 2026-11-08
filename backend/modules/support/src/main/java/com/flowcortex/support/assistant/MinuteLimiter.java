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
 * At most ten questions a minute per asker and, for guests, per network, as in-memory token buckets.
 * Per instance: with N backend replicas an asker could reach N times the rate; the daily quotas stay
 * exact. A bucket is dropped one window after it was made, so the size bound only guards memory.
 * Network buckets sit in their own cache: a flood of client-minted session keys cannot evict them,
 * and the network cap is what stops that flood.
 */
@Component
class MinuteLimiter {

    static final int PER_MINUTE = 10;

    private static final Duration PERIOD = Duration.ofMinutes(1);
    private static final long MAX_TRACKED_KEYS = 100_000;

    private final Cache<String, Bucket> buckets = newCache();
    private final Cache<String, Bucket> networkBuckets = newCache();
    private final Clock clock;

    MinuteLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Takes one question from the asker's bucket, or refuses with 429 and the time of the next allowed one. */
    void acquire(String subjectKey) {
        take(buckets, subjectKey);
    }

    /** Takes one question from the network's bucket, or refuses like {@link #acquire}. */
    void acquireNetwork(String networkKey) {
        take(networkBuckets, networkKey);
    }

    private void take(Cache<String, Bucket> cache, String key) {
        Bucket bucket = cache.get(key, ignored -> Bucket.builder()
                .addLimit(limit -> limit.capacity(PER_MINUTE).refillGreedy(PER_MINUTE, PERIOD))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw QuotaService.exhausted(Instant.now(clock).plusNanos(probe.getNanosToWaitForRefill()));
        }
    }

    private static Cache<String, Bucket> newCache() {
        return Caffeine.newBuilder().expireAfterWrite(PERIOD).maximumSize(MAX_TRACKED_KEYS).build();
    }
}
