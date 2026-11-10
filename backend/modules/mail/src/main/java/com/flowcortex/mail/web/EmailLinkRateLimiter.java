package com.flowcortex.mail.web;

import com.flowcortex.mail.application.MailProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One in-memory token bucket per (token hash, client address), shared by the three email-link routes.
 * Per instance: N backend replicas admit N times the configured rate.
 */
@Component
class EmailLinkRateLimiter {

    /**
     * Caps memory under a flood of distinct tokens; evicting a bucket only forgets its spent calls, so
     * the cap can loosen the limit but never block anyone.
     */
    private static final long MAX_TRACKED_KEYS = 100_000;

    private final MailProperties.RateLimit limit;
    private final Cache<String, Bucket> buckets;

    @Autowired
    EmailLinkRateLimiter(MailProperties properties) {
        this(properties.links().rateLimit());
    }

    EmailLinkRateLimiter(MailProperties.RateLimit limit) {
        this.limit = limit;
        // A bucket idle for its whole period is full again, so dropping it then changes no answer.
        this.buckets = Caffeine.newBuilder().expireAfterAccess(limit.period()).maximumSize(MAX_TRACKED_KEYS).build();
    }

    /** Empty when the call is admitted; otherwise how long until one more call is admitted. */
    Optional<Duration> tryAcquire(String tokenHash, String clientAddress) {
        Bucket bucket = buckets.get(tokenHash + "|" + clientAddress, ignored -> Bucket.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
}
