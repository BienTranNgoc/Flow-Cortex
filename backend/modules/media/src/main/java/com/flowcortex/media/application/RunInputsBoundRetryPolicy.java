package com.flowcortex.media.application;

import com.flowcortex.jobs.api.PublicationRetryPolicy;
import com.flowcortex.media.api.RunInputsBound;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * A listener that cannot record the binding (its database is down) is redelivered for about a day:
 * 288 attempts, waiting 1, 2, 4 and then 5 minutes after the previous one began.
 */
@Component
class RunInputsBoundRetryPolicy implements PublicationRetryPolicy {

    private static final int MAX_ATTEMPTS = 288;
    private static final int LONGEST_WAIT_MINUTES = 5;

    @Override
    public Class<?> eventType() {
        return RunInputsBound.class;
    }

    @Override
    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    @Override
    public Duration delayAfter(int attempts) {
        int doublings = Math.min(Math.max(attempts, 1) - 1, 3);
        return Duration.ofMinutes(Math.min(1L << doublings, LONGEST_WAIT_MINUTES));
    }
}
