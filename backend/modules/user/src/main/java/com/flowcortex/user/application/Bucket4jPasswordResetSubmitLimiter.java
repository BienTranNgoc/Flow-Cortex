package com.flowcortex.user.application;

import com.flowcortex.user.passwordreset.PasswordResetProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One in-memory token bucket per client IP, refilled gradually, spent by every new-password
 * submission. Per instance: N backend replicas admit N times the configured rate.
 */
@Component
class Bucket4jPasswordResetSubmitLimiter implements PasswordResetSubmitLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jPasswordResetSubmitLimiter.class);

    /** Same bound and reasoning as {@link PerIpAndEmailBuckets}. */
    private static final long MAX_TRACKED_KEYS = 100_000;

    private final RateLimit limit;
    private final Cache<String, Bucket> buckets;

    Bucket4jPasswordResetSubmitLimiter(PasswordResetProperties properties) {
        this.limit = properties.submitPerIp();
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(limit.period())
                .maximumSize(MAX_TRACKED_KEYS)
                .build();
    }

    @Override
    public void acquire(String clientIp) {
        Bucket bucket = buckets.get(clientIp, ignored -> Bucket.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return;
        }
        PasswordResetRateLimitedException rejected =
                new PasswordResetRateLimitedException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        LOG.info("password_reset outcome=submit_rate_limited limit=ip capacity={} period={} retryAfterSeconds={}",
                limit.capacity(), limit.period(), rejected.getRetryAfterSeconds());
        throw rejected;
    }
}
