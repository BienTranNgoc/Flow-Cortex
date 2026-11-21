package com.flowcortex.user.phone;

import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Token bucket per client IP for send-code requests, shared by every backend instance. */
@Component
class PhoneSendIpLimiter {

    private final RateLimiter limiter;

    PhoneSendIpLimiter(RateLimiters rateLimiters) {
        this.limiter = rateLimiters.named(PhoneRateLimits.PHONE_SEND_IP);
    }

    /** Takes one token; {@link Duration#ZERO} when taken, else how long until one is free. */
    Duration tryAcquire(String clientIp) {
        return limiter.tryAcquire(clientIp).orElse(Duration.ZERO);
    }
}
