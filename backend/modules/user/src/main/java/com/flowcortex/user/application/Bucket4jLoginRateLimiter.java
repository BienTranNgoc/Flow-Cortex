package com.flowcortex.user.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One in-memory token bucket per client IP, refilled gradually. Per instance: N backend replicas
 * admit N times the configured rate. The throttle is per IP only and counts failed attempts. A
 * guess spread over many IPs is bounded per account instead: after five wrong passwords the
 * account refuses every login, right password or wrong, until the lock window ends (see {@link
 * LoginAttemptRecorder}). The price is that anyone who can name an account can lock it out.
 */
@Component
class Bucket4jLoginRateLimiter implements LoginRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jLoginRateLimiter.class);

    /** Same bound and reasoning as {@link Bucket4jRegistrationRateLimiter}. */
    private static final long MAX_TRACKED_KEYS = 100_000;

    private final RegistrationProperties.Limit limit;
    private final Cache<String, Bucket> buckets;

    Bucket4jLoginRateLimiter(LoginThrottleProperties properties) {
        this.limit = properties.failuresPerIp();
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(limit.period())
                .maximumSize(MAX_TRACKED_KEYS)
                .build();
    }

    @Override
    public void requireWithinLimit(String clientIp) {
        Bucket bucket = buckets.getIfPresent(clientIp);
        if (bucket == null) {
            return;
        }
        EstimationProbe probe = bucket.estimateAbilityToConsume(1);
        if (!probe.canBeConsumed()) {
            LoginRateLimitedException rejected =
                    new LoginRateLimitedException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
            LOG.info("Login rate-limited: capacity={} period={} retryAfterSeconds={}", limit.capacity(),
                    limit.period(), rejected.getRetryAfterSeconds());
            throw rejected;
        }
    }

    @Override
    public void recordFailure(String clientIp) {
        buckets.get(clientIp, ignored -> Bucket.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()))
                .build()).tryConsume(1);
    }
}
