package com.flowcortex.shared.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only store: local Bucket4j buckets in a map, driven by a {@link Clock} so refills are deterministic. */
public final class InMemoryRateLimitStore implements RateLimitStore {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final TimeMeter time;

    public InMemoryRateLimitStore() {
        this(Clock.systemUTC());
    }

    public InMemoryRateLimitStore(Clock clock) {
        this.time = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return clock.millis() * 1_000_000L;
            }

            @Override
            public boolean isWallClockBased() {
                return true;
            }
        };
    }

    @Override
    public Optional<Duration> tryConsume(String key, long capacity, Duration period) {
        ConsumptionProbe probe = bucket(key, capacity, period).tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    @Override
    public Optional<Duration> estimate(String key, long capacity, Duration period) {
        EstimationProbe probe = bucket(key, capacity, period).estimateAbilityToConsume(1);
        return probe.canBeConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    @Override
    public void consume(String key, long capacity, Duration period) {
        bucket(key, capacity, period).tryConsume(1);
    }

    private Bucket bucket(String key, long capacity, Duration period) {
        return buckets.computeIfAbsent(key, ignored -> Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, period))
                .withCustomTimePrecision(time)
                .build());
    }
}
