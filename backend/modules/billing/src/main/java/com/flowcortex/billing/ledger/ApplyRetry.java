package com.flowcortex.billing.ledger;

import java.time.Duration;
import java.time.Instant;

/**
 * When to try a callback again after applying it failed: the wait doubles from the base up to the cap, and
 * the last allowed attempt gives up so one callback cannot be retried for ever.
 */
public final class ApplyRetry {

    static final int MAX_ATTEMPTS = 10;
    static final Duration BASE_WAIT = Duration.ofSeconds(30);
    static final Duration MAX_WAIT = Duration.ofHours(1);

    private ApplyRetry() {
    }

    public sealed interface Outcome permits RetryAt, Exhausted {
    }

    public record RetryAt(Instant at) implements Outcome {
    }

    public record Exhausted() implements Outcome {
    }

    /**
     * @param attemptsSoFar failed attempts already recorded, before the one that just failed
     * @param now the time of the failure
     * @throws IllegalArgumentException when {@code attemptsSoFar} is negative
     */
    public static Outcome afterFailure(int attemptsSoFar, Instant now) {
        if (attemptsSoFar < 0) {
            throw new IllegalArgumentException("attemptsSoFar must not be negative: " + attemptsSoFar);
        }
        if (attemptsSoFar >= MAX_ATTEMPTS - 1) {
            return new Exhausted();
        }
        Duration wait = BASE_WAIT.multipliedBy(1L << attemptsSoFar);
        return new RetryAt(now.plus(wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait));
    }
}
