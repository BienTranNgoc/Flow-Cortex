package com.flowcortex.user.application;

import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One token bucket per client IP, refilled gradually and shared by every backend instance. The
 * throttle is per IP only and counts failed attempts. A guess spread over many IPs is bounded per
 * account instead: after five wrong passwords the account refuses every login, right password or
 * wrong, until the lock window ends (see {@link LoginAttemptRecorder}). The price is that anyone
 * who can name an account can lock it out.
 */
@Component
class Bucket4jLoginRateLimiter implements LoginRateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(Bucket4jLoginRateLimiter.class);

    private final RateLimiter limiter;

    Bucket4jLoginRateLimiter(RateLimiters rateLimiters) {
        this.limiter = rateLimiters.named(UserRateLimits.LOGIN_FAILURES_IP);
    }

    @Override
    public void requireWithinLimit(String clientIp) {
        Optional<Duration> wait = limiter.estimate(clientIp);
        if (wait.isPresent()) {
            LoginRateLimitedException rejected = new LoginRateLimitedException(wait.get());
            LOG.info("Login rate-limited: capacity={} period={} retryAfterSeconds={}",
                    limiter.definition().capacity(), limiter.definition().period(), rejected.getRetryAfterSeconds());
            throw rejected;
        }
    }

    @Override
    public void recordFailure(String clientIp) {
        limiter.spend(clientIp);
    }
}
