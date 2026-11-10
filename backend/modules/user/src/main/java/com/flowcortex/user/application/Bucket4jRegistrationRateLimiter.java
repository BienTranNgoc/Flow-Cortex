package com.flowcortex.user.application;

import com.flowcortex.shared.ratelimit.RateLimiters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Registration attempts limited per client IP and per normalised email (see {@link PerIpAndEmailBuckets}). */
@Component
class Bucket4jRegistrationRateLimiter implements RegistrationRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jRegistrationRateLimiter.class);

    private final PerIpAndEmailBuckets buckets;

    Bucket4jRegistrationRateLimiter(RateLimiters rateLimiters) {
        this.buckets = new PerIpAndEmailBuckets(
                rateLimiters.named(UserRateLimits.REGISTRATION_IP), rateLimiters.named(UserRateLimits.REGISTRATION_EMAIL));
    }

    @Override
    public void acquire(String clientIp, String email) {
        buckets.tryAcquire(clientIp, email).ifPresent(refusal -> {
            RegistrationRateLimitedException rejected = new RegistrationRateLimitedException(refusal.retryAfter());
            LOG.info("Registration rate-limited: limit={} capacity={} period={} retryAfterSeconds={}",
                    refusal.dimension(), refusal.limit().capacity(), refusal.limit().period(),
                    rejected.getRetryAfterSeconds());
            throw rejected;
        });
    }
}
