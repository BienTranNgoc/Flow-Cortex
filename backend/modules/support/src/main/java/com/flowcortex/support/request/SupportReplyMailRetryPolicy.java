package com.flowcortex.support.request;

import com.flowcortex.jobs.api.PublicationRetryPolicy;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** Six attempts in all: the first send, then retries 1, 5, 15, 60 and 240 minutes after the previous began. */
@Component
class SupportReplyMailRetryPolicy implements PublicationRetryPolicy {

    private static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofMinutes(60), Duration.ofMinutes(240));

    @Override
    public Class<?> eventType() {
        return SupportReplyMailRequested.class;
    }

    @Override
    public int maxAttempts() {
        return DELAYS.size() + 1;
    }

    @Override
    public Duration delayAfter(int attempts) {
        return DELAYS.get(Math.min(Math.max(attempts, 1), DELAYS.size()) - 1);
    }
}
