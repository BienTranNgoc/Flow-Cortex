package com.flowcortex.user.application;

import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import com.flowcortex.shared.ratelimit.RateLimiter;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Token buckets on the shared registry, one per client IP and one per normalised email, refilled
 * gradually over each limit's period.
 */
final class PerIpAndEmailBuckets {

    /** Which limit refused ({@code "ip"} or {@code "email"}) and how long until one attempt refills. */
    record Refusal(String dimension, RateLimit limit, Duration retryAfter) {
    }

    private final RateLimiter perIp;
    private final RateLimiter perEmail;

    PerIpAndEmailBuckets(RateLimiter perIp, RateLimiter perEmail) {
        this.perIp = perIp;
        this.perEmail = perEmail;
    }

    /** Empty when admitted. The IP is spent first; an IP refusal leaves the email bucket untouched. */
    Optional<Refusal> tryAcquire(String clientIp, String email) {
        Optional<Refusal> ipRefusal = consume("ip", perIp, clientIp);
        if (ipRefusal.isPresent()) {
            return ipRefusal;
        }
        return consume("email", perEmail, email.trim().toLowerCase(Locale.ROOT));
    }

    private static Optional<Refusal> consume(String dimension, RateLimiter limiter, String identity) {
        RateLimitDefinition definition = limiter.definition();
        RateLimit limit = new RateLimit(Math.toIntExact(definition.capacity()), definition.period());
        return limiter.tryAcquire(identity).map(wait -> new Refusal(dimension, limit, wait));
    }
}
