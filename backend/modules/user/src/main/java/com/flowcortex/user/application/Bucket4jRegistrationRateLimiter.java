package com.flowcortex.user.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets, one per client IP and one per normalised email, refilled gradually
 * over each limit's period. Per instance: N backend replicas admit N times the configured rate.
 */
@Component
class Bucket4jRegistrationRateLimiter implements RegistrationRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jRegistrationRateLimiter.class);

    /**
     * Caps memory under a flood of distinct keys; evicting a bucket only forgets its spent
     * attempts, so the cap can loosen a limit but never block anyone.
     */
    private static final long MAX_TRACKED_KEYS = 100_000;

    private final RegistrationProperties.Limit perIp;
    private final RegistrationProperties.Limit perEmail;
    private final Cache<String, Bucket> buckets;

    Bucket4jRegistrationRateLimiter(RegistrationProperties properties) {
        this.perIp = properties.perIp();
        this.perEmail = properties.perEmail();
        // A bucket idle for its whole period is full again, so dropping it after the longer
        // period changes no answer.
        Duration longestPeriod = perIp.period().compareTo(perEmail.period()) >= 0
                ? perIp.period() : perEmail.period();
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(longestPeriod)
                .maximumSize(MAX_TRACKED_KEYS)
                .build();
    }

    @Override
    public void acquire(String clientIp, String email) {
        consume("ip", "ip:" + clientIp, perIp);
        consume("email", "email:" + email.trim().toLowerCase(Locale.ROOT), perEmail);
    }

    private void consume(String dimension, String key, RegistrationProperties.Limit limit) {
        Bucket bucket = buckets.get(key, ignored -> Bucket.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            RegistrationRateLimitedException rejected =
                    new RegistrationRateLimitedException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
            LOG.info("Registration rate-limited: limit={} capacity={} period={} retryAfterSeconds={}", dimension,
                    limit.capacity(), limit.period(), rejected.getRetryAfterSeconds());
            throw rejected;
        }
    }
}
