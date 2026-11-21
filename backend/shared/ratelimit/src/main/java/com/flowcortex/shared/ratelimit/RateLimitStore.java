package com.flowcortex.shared.ratelimit;

import java.time.Duration;
import java.util.Optional;

/**
 * Token-bucket storage keyed by an opaque string. An empty result means admitted; otherwise it
 * is the time until one unit is available. Implementations may throw; {@link RateLimiter} fails open.
 */
public interface RateLimitStore {

    /** Spends one unit when available. */
    Optional<Duration> tryConsume(String key, long capacity, Duration period);

    /** Answers whether one unit is available without spending it. */
    Optional<Duration> estimate(String key, long capacity, Duration period);

    /** Spends one unit when available and answers nothing. */
    void consume(String key, long capacity, Duration period);
}
