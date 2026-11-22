package com.flowcortex.shared.ratelimit;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * One named limit: at most {@code capacity} units per {@code period}, refilled gradually.
 * Declared once by the module that owns the limit and looked up by name through {@link RateLimiters}.
 */
public record RateLimitDefinition(String name, long capacity, Duration period) {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");

    public RateLimitDefinition {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Rate limiter name '" + name + "' must be kebab-case: a lowercase letter, then lowercase letters,"
                            + " digits or hyphens, at most 40 characters");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Rate limiter '" + name + "' needs a positive capacity, got " + capacity);
        }
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("Rate limiter '" + name + "' needs a positive period, got " + period);
        }
    }
}
