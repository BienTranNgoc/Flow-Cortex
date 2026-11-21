package com.flowcortex.jobs.application;

import com.flowcortex.jobs.api.PublicationRetryPolicy;
import java.time.Duration;

/** The policy of every event type that contributes none: ten attempts, a minute apart. */
final class DefaultPublicationRetryPolicy implements PublicationRetryPolicy {

    @Override
    public Class<?> eventType() {
        return Object.class;
    }

    @Override
    public int maxAttempts() {
        return 10;
    }

    @Override
    public Duration delayAfter(int attempts) {
        return Duration.ofMinutes(1);
    }
}
