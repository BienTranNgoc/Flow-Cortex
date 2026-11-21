package com.flowcortex.user.application;

import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One token bucket per client IP, refilled gradually and shared by every backend instance, spent
 * by every new-password submission.
 */
@Component
class Bucket4jPasswordResetSubmitLimiter implements PasswordResetSubmitLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jPasswordResetSubmitLimiter.class);

    private final RateLimiter limiter;

    Bucket4jPasswordResetSubmitLimiter(RateLimiters rateLimiters) {
        this.limiter = rateLimiters.named(UserRateLimits.PASSWORD_RESET_SUBMIT_IP);
    }

    @Override
    public void acquire(String clientIp) {
        limiter.tryAcquire(clientIp).ifPresent(wait -> {
            PasswordResetRateLimitedException rejected = new PasswordResetRateLimitedException(wait);
            LOG.info("password_reset outcome=submit_rate_limited limit=ip capacity={} period={} retryAfterSeconds={}",
                    limiter.definition().capacity(), limiter.definition().period(), rejected.getRetryAfterSeconds());
            throw rejected;
        });
    }
}
