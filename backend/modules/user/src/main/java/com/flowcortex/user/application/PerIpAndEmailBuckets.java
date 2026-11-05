package com.flowcortex.user.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * In-memory token buckets, one per client IP and one per normalised email, refilled gradually
 * over each limit's period. Per instance: N backend replicas admit N times the configured rate.
 */
final class PerIpAndEmailBuckets {

    /**
     * Caps memory under a flood of distinct keys; evicting a bucket only forgets its spent
     * attempts, so the cap can loosen a limit but never block anyone.
     */
    private static final long MAX_TRACKED_KEYS = 100_000;

    /** Which limit refused ({@code "ip"} or {@code "email"}) and how long until one attempt refills. */
    record Refusal(String dimension, RateLimit limit, Duration retryAfter) {
    }

    private final RateLimit perIp;
    private final RateLimit perEmail;
    private final Cache<String, Bucket> buckets;

    PerIpAndEmailBuckets(RateLimit perIp, RateLimit perEmail) {
        this.perIp = perIp;
        this.perEmail = perEmail;
        // A bucket idle for its whole period is full again, so dropping it after the longer
        // period changes no answer.
        Duration longestPeriod = perIp.period().compareTo(perEmail.period()) >= 0
                ? perIp.period() : perEmail.period();
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(longestPeriod)
                .maximumSize(MAX_TRACKED_KEYS)
                .build();
    }

    /** Empty when admitted. The IP is spent first; an IP refusal leaves the email bucket untouched. */
    Optional<Refusal> tryAcquire(String clientIp, String email) {
        Optional<Refusal> ipRefusal = consume("ip", "ip:" + clientIp, perIp);
        if (ipRefusal.isPresent()) {
            return ipRefusal;
        }
        return consume("email", "email:" + email.trim().toLowerCase(Locale.ROOT), perEmail);
    }

    private Optional<Refusal> consume(String dimension, String key, RateLimit limit) {
        Bucket bucket = buckets.get(key, ignored -> Bucket.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Optional.empty();
        }
        return Optional.of(new Refusal(dimension, limit, Duration.ofNanos(probe.getNanosToWaitForRefill())));
    }
}
