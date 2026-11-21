package com.flowcortex.jobs.api;

import java.time.Duration;

/**
 * How a failed publication of one event type is retried. The module that owns the event contributes it
 * as a bean; an event type without one is retried ten times, one minute apart.
 */
public interface PublicationRetryPolicy {

    /** The event class this policy applies to; at most one policy per class. */
    Class<?> eventType();

    /** The attempt count at which the publication is given up and deleted. */
    int maxAttempts();

    /** The wait after the attempt numbered {@code attempts} began, before the next attempt. */
    Duration delayAfter(int attempts);
}
