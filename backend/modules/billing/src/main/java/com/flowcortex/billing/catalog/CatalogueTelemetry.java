package com.flowcortex.billing.catalog;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Catalogue reads and check passes. Every tag is a fixed word and no log line carries request data, so
 * cardinality is bounded and nothing about the caller can reach the log.
 */
@Component
class CatalogueTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueTelemetry.class);

    private final MeterRegistry meters;

    CatalogueTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Runs a catalogue read; a failure is counted, logged once with its class, and rethrown unchanged. */
    <T> T observedRead(String catalogue, Supplier<T> read) {
        Timer.Sample sample = Timer.start(meters);
        try {
            T result = read.get();
            sample.stop(readTimer(catalogue, "success"));
            return result;
        } catch (RuntimeException e) {
            sample.stop(readTimer(catalogue, "failure"));
            LOG.error("billing_catalogue_read catalogue={} outcome=failure error={}", catalogue,
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    void recordCheck(String trigger, String outcome, Duration took) {
        Timer.builder("billing.catalogue.check")
                .description("Catalogue check passes by trigger and outcome")
                .tag("trigger", trigger)
                .tag("outcome", outcome)
                .register(meters)
                .record(took);
    }

    void recordViolation(String kind) {
        meters.counter("billing.catalogue.violation", "kind", kind).increment();
    }

    private Timer readTimer(String catalogue, String outcome) {
        return Timer.builder("billing.catalogue.read")
                .description("Public catalogue reads by catalogue and outcome")
                .tag("catalogue", catalogue)
                .tag("outcome", outcome)
                .register(meters);
    }
}
