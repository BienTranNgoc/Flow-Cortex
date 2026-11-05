package com.flowcortex.user.application;

import com.flowcortex.user.passwordreset.PasswordResetProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Forgot-password requests limited per client IP and per normalised email (see {@link PerIpAndEmailBuckets}). */
@Component
class Bucket4jPasswordResetRateLimiter implements PasswordResetRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jPasswordResetRateLimiter.class);

    private final PerIpAndEmailBuckets buckets;

    Bucket4jPasswordResetRateLimiter(PasswordResetProperties properties) {
        this.buckets = new PerIpAndEmailBuckets(properties.perIp(), properties.perEmail());
    }

    @Override
    public void acquire(String clientIp, String email) {
        buckets.tryAcquire(clientIp, email).ifPresent(refusal -> {
            PasswordResetRateLimitedException rejected = new PasswordResetRateLimitedException(refusal.retryAfter());
            LOG.info("password_reset outcome=rate_limited limit={} capacity={} period={} retryAfterSeconds={}",
                    refusal.dimension(), refusal.limit().capacity(), refusal.limit().period(),
                    rejected.getRetryAfterSeconds());
            throw rejected;
        });
    }
}
