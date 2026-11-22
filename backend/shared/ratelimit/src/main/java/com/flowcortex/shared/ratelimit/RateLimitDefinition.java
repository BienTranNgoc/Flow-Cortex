package com.flowcortex.shared.ratelimit;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * One named limit: at most {@code capacity} units per {@code period}, refilled gradually.
 * Declared once by the module that owns the limit and looked up by name through {@link RateLimiters}.
 * When the store fails, a limiter admits the call by default; {@code failClosed} refuses it instead,
 * for limits that are the only guard against brute force.
 */
public record RateLimitDefinition(String name, long capacity, Duration period, boolean failClosed) {

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");

    public RateLimitDefinition(String name, long capacity, Duration period) {
        this(name, capacity, period, false);
    }

    public static RateLimitDefinition failClosed(String name, long capacity, Duration period) {
        return new RateLimitDefinition(name, capacity, period, true);
    }

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
