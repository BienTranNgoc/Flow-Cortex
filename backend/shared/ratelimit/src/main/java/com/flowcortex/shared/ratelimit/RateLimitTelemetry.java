package com.flowcortex.shared.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counters and log lines for refusals and store failures. Recording never throws, so a broken
 * meter registry or appender cannot change an answer, and the failure line is written at most once
 * a minute per limiter so a store outage cannot flood the logs.
 */
public class RateLimitTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitTelemetry.class);
    private static final Duration LOG_INTERVAL = Duration.ofMinutes(1);
    private static final String TELEMETRY_FAILURES = "telemetry";

    private final MeterRegistry meters;
    private final Clock clock;
    private final ConcurrentMap<String, Throttle> throttles = new ConcurrentHashMap<>();

    public RateLimitTelemetry(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    /** One refusal: counter tagged only with the limiter, and an INFO line with the 12-hex identity. */
    public void rejected(RateLimitDefinition definition, String identity, Duration retryAfter) {
        try {
            meters.counter("rate_limit.rejections", "limiter", definition.name()).increment();
            LOG.info("rate_limit_rejected limiter={} capacity={} period={} retryAfterSeconds={} identity={}",
                    definition.name(), definition.capacity(), definition.period(), retryAfterSeconds(retryAfter),
                    RateLimitKeys.logIdentity(definition, identity));
        } catch (RuntimeException e) {
            telemetryFailed(e);
        }
    }

    /** One store failure that was admitted through: counter every time, ERROR line once a minute. */
    public void storeFailed(RateLimitDefinition definition, Exception cause) {
        try {
            meters.counter("rate_limit.store.failures", "limiter", definition.name()).increment();
        } catch (RuntimeException e) {
            telemetryFailed(e);
        }
        try {
            long suppressed = throttle(definition.name()).acquire(clock.instant());
            if (suppressed >= 0) {
                LOG.error("rate_limit_store_failed limiter={} suppressed={} admittedWithoutLimit=true",
                        definition.name(), suppressed, cause);
            }
        } catch (RuntimeException e) {
            telemetryFailed(e);
        }
    }

    private void telemetryFailed(RuntimeException failure) {
        try {
            long suppressed = throttle(TELEMETRY_FAILURES).acquire(clock.instant());
            if (suppressed >= 0) {
                LOG.warn("rate_limit_telemetry_failed suppressed={}", suppressed, failure);
            }
        } catch (RuntimeException ignored) {
            // Last resort: the logger itself failed, nothing is left to report through.
        }
    }

    private Throttle throttle(String key) {
        return throttles.computeIfAbsent(key, ignored -> new Throttle());
    }

    private static long retryAfterSeconds(Duration retryAfter) {
        long seconds = (retryAfter.toNanos() + 999_999_999L) / 1_000_000_000L;
        return Math.max(1, seconds);
    }

    /** Lets one event per {@link #LOG_INTERVAL} through and counts the ones held back since. */
    private static final class Throttle {

        private Instant lastLogged;
        private long suppressed;

        /** The number of events held back since the previous line when this one may log, else -1. */
        synchronized long acquire(Instant now) {
            if (lastLogged == null || !now.isBefore(lastLogged.plus(LOG_INTERVAL))) {
                long held = suppressed;
                suppressed = 0;
                lastLogged = now;
                return held;
            }
            suppressed++;
            return -1;
        }
    }
}
