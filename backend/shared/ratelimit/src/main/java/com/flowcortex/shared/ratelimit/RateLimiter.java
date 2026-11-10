package com.flowcortex.shared.ratelimit;

import java.time.Duration;
import java.util.Optional;

/**
 * One declared limit over a shared store. A failing store never blocks a request: the call is
 * admitted and the failure is counted and logged.
 */
public final class RateLimiter {

    private final RateLimitDefinition definition;
    private final RateLimitStore store;
    private final RateLimitTelemetry telemetry;

    public RateLimiter(RateLimitDefinition definition, RateLimitStore store, RateLimitTelemetry telemetry) {
        this.definition = definition;
        this.store = store;
        this.telemetry = telemetry;
    }

    public RateLimitDefinition definition() {
        return definition;
    }

    /** Spends one unit for the identity. Empty when admitted, else the wait until one unit refills. */
    public Optional<Duration> tryAcquire(String identity) {
        try {
            Optional<Duration> wait = store.tryConsume(
                    RateLimitKeys.storageKey(definition, identity), definition.capacity(), definition.period());
            wait.ifPresent(retryAfter -> telemetry.rejected(definition, identity, retryAfter));
            return wait;
        } catch (RuntimeException e) {
            telemetry.storeFailed(definition, e);
            return Optional.empty();
        }
    }

    /** Whether one unit is available, without spending it. Empty when available, else the wait. */
    public Optional<Duration> estimate(String identity) {
        try {
            return store.estimate(
                    RateLimitKeys.storageKey(definition, identity), definition.capacity(), definition.period());
        } catch (RuntimeException e) {
            telemetry.storeFailed(definition, e);
            return Optional.empty();
        }
    }

    /** Spends one unit without asking for an answer (a failure counted after the fact). */
    public void spend(String identity) {
        try {
            store.consume(RateLimitKeys.storageKey(definition, identity), definition.capacity(), definition.period());
        } catch (RuntimeException e) {
            telemetry.storeFailed(definition, e);
        }
    }
}
