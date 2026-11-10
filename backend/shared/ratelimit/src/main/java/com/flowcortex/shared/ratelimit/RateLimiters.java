package com.flowcortex.shared.ratelimit;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Every declared limit, by name. Construction fails on a duplicate name and {@link #named} on a
 * name nobody declared, so a typo stops the application at startup instead of leaving a path unlimited.
 */
public class RateLimiters {

    private final Map<String, RateLimiter> byName = new HashMap<>();

    public RateLimiters(Collection<RateLimitDefinition> definitions, RateLimitStore store, RateLimitTelemetry telemetry) {
        for (RateLimitDefinition definition : definitions) {
            RateLimiter limiter = new RateLimiter(definition, store, telemetry);
            if (byName.putIfAbsent(definition.name(), limiter) != null) {
                throw new IllegalStateException("Rate limiter '" + definition.name() + "' is declared more than once");
            }
        }
    }

    public RateLimiter named(String name) {
        RateLimiter limiter = byName.get(name);
        if (limiter == null) {
            throw new IllegalStateException("Rate limiter '" + name + "' is not declared; known limiters: "
                    + byName.keySet().stream().sorted().toList());
        }
        return limiter;
    }
}
