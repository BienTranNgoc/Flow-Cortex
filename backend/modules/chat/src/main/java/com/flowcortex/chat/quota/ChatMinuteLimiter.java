package com.flowcortex.chat.quota;

import com.flowcortex.chat.web.ChatProblemException;
import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * At most {@code per-minute} sends a minute per member. The budget lives in the shared rate-limit store,
 * so every backend instance draws from the same one.
 */
@Component("chatMinuteLimiter")
class ChatMinuteLimiter {

    private final RateLimiter members;
    private final Clock clock;

    ChatMinuteLimiter(RateLimiters limiters, Clock clock) {
        this.members = limiters.named(ChatRateLimits.MEMBER_MINUTE);
        this.clock = clock;
    }

    /** Takes one send from the member's budget, or refuses with 429 and the time of the next allowed one. */
    void acquire(UUID memberId) {
        members.tryAcquire(memberId.toString()).ifPresent(wait -> {
            throw ChatProblemException.rateLimited(Instant.now(clock).plus(wait));
        });
    }
}
