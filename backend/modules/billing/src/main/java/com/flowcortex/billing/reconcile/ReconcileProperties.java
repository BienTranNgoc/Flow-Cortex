package com.flowcortex.billing.reconcile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.billing.reconcile.*}: whether each pass asks the provider, how often each pass runs,
 * how many payments it takes per run, and the age at which an unresolved payment is first asked about.
 * A value outside its range refuses startup and names the property.
 */
@ConfigurationProperties("flowcortex.billing.reconcile")
public record ReconcileProperties(Ageing ageing, Daily daily, Duration floor) {

    static final int MAX_BATCH_SIZE = 5000;
    private static final Duration MIN_PERIOD = Duration.ofSeconds(30);
    private static final Duration MAX_PERIOD = Duration.ofHours(1);
    private static final String PREFIX = "flowcortex.billing.reconcile.";

    public ReconcileProperties {
        ageing = ageing == null ? new Ageing(null, null, null) : ageing;
        daily = daily == null ? new Daily(null, null, null) : daily;
        floor = period(PREFIX + "floor", floor, Duration.ofMinutes(15));
    }

    /** The ladder pass over payments still {@code pending}. */
    public record Ageing(Boolean enabled, Duration interval, Integer batchSize) {

        public Ageing {
            enabled = enabled == null || enabled;
            interval = period(PREFIX + "ageing.interval", interval, Duration.ofMinutes(5));
            batchSize = batch(PREFIX + "ageing.batch-size", batchSize);
        }
    }

    /** The backstop pass over payments whatever their status. */
    public record Daily(Boolean enabled, Duration interval, Integer batchSize) {

        public Daily {
            enabled = enabled == null || enabled;
            interval = period(PREFIX + "daily.interval", interval, Duration.ofMinutes(10));
            batchSize = batch(PREFIX + "daily.batch-size", batchSize);
        }
    }

    private static Duration period(String property, Duration value, Duration fallback) {
        Duration period = value == null ? fallback : value;
        if (period.compareTo(MIN_PERIOD) < 0 || period.compareTo(MAX_PERIOD) > 0) {
            throw new IllegalArgumentException(property + " must be from 30 seconds to 1 hour: " + period);
        }
        return period;
    }

    private static int batch(String property, Integer value) {
        int size = value == null ? 50 : value;
        if (size < 1 || size > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(property + " must be from 1 to " + MAX_BATCH_SIZE + ": " + size);
        }
        return size;
    }
}
