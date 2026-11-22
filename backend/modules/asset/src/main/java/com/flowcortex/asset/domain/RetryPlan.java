package com.flowcortex.asset.domain;

import java.time.Duration;

/** Backoff and limits of the registration queue; pure so the schedule is locked by its own test. */
public final class RetryPlan {

    public static final Duration LEASE = Duration.ofMinutes(5);
    public static final int MAX_ATTEMPTS = 288;
    public static final Duration RETENTION = Duration.ofDays(30);

    private static final long MAX_WAIT_MINUTES = 5;
    // 2^3 = 8 already exceeds the cap; stopping the shift here keeps it from overflowing.
    private static final int MAX_SHIFT = 3;

    private RetryPlan() {
    }

    /** What to do after the {@code failures}-th consecutive failure (1-based). */
    public static RetryDecision after(int failures) {
        if (failures >= MAX_ATTEMPTS) {
            return Stuck.INSTANCE;
        }
        int shift = Math.min(Math.max(failures - 1, 0), MAX_SHIFT);
        return new Pending(Duration.ofMinutes(Math.min(1L << shift, MAX_WAIT_MINUTES)));
    }

    /** The outcome of a failure. */
    public sealed interface RetryDecision permits Pending, Stuck {
    }

    /** Try again after {@code delay}. */
    public record Pending(Duration delay) implements RetryDecision {
    }

    /** The attempt budget is spent. */
    public enum Stuck implements RetryDecision {
        INSTANCE
    }
}
