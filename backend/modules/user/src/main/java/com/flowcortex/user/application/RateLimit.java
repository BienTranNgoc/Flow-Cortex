package com.flowcortex.user.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

/** At most {@code capacity} attempts per {@code period}, refilled gradually. */
public record RateLimit(@Positive int capacity, @NotNull Duration period) {

    public RateLimit {
        if (period != null && (period.isZero() || period.isNegative())) {
            throw new IllegalArgumentException("rate-limit period must be positive, was " + period);
        }
    }
}
