package com.flowcortex.chat.quota;

import com.flowcortex.chat.config.ChatProperties;
import com.flowcortex.chat.web.ChatProblemException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * At most {@code per-minute} sends a minute per member, as an in-memory token bucket per instance: with N
 * replicas a member could reach N times the rate while the daily quotas stay exact. A bucket is dropped one
 * window after it was made, so the size bound only guards memory.
 */
@Component("chatMinuteLimiter")
class ChatMinuteLimiter {

    private static final Duration PERIOD = Duration.ofMinutes(1);
    private static final long MAX_TRACKED_MEMBERS = 100_000;

    private final Cache<UUID, Bucket> buckets = Caffeine.newBuilder().expireAfterWrite(PERIOD)
            .maximumSize(MAX_TRACKED_MEMBERS).build();
    private final int perMinute;
    private final Clock clock;

    ChatMinuteLimiter(ChatProperties properties, Clock clock) {
        this.perMinute = properties.quota().perMinute();
        this.clock = clock;
    }

    /** Takes one send from the member's bucket, or refuses with 429 and the time of the next allowed one. */
    void acquire(UUID memberId) {
        Bucket bucket = buckets.get(memberId, ignored -> Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, PERIOD)).build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw ChatProblemException.rateLimited(
                    Instant.now(clock).plusNanos(probe.getNanosToWaitForRefill()));
        }
    }
}
