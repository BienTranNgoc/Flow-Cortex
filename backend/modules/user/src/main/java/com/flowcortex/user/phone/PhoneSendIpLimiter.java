package com.flowcortex.user.phone;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * In-memory token bucket per client IP for send-code requests, refilled gradually over the
 * period. Per instance: N backend replicas admit N times the configured rate.
 */
@Component
class PhoneSendIpLimiter {

    /** Evicting a bucket only forgets spent requests, so the cap can loosen the limit but never block. */
    private static final long MAX_TRACKED_IPS = 100_000;

    private final PhoneSendLimits.IpLimit limit;
    private final Cache<String, Bucket> buckets;

    PhoneSendIpLimiter(PhoneSendLimits limits) {
        this.limit = limits.perIp();
        // A bucket idle for a whole period is full again, so dropping it then changes no answer.
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(limit.period())
                .maximumSize(MAX_TRACKED_IPS)
                .build();
    }

    /** Takes one token; {@link Duration#ZERO} when taken, else how long until one is free. */
    Duration tryAcquire(String clientIp) {
        Bucket bucket = buckets.get(clientIp, ignored -> Bucket.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Duration.ZERO : Duration.ofNanos(probe.getNanosToWaitForRefill());
    }
}
