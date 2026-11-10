package com.flowcortex.llm.gemini;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one provider attempt leaves behind: the {@code llm.requests} timer (tags {@code model} and
 * {@code outcome} only), an {@code llm.attempt} observation and one log line. None of them carries the
 * prompt, the answer, a provider message or the key.
 */
final class GeminiTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiTelemetry.class);

    static final String OBSERVATION = "llm.attempt";

    private final MeterRegistry meters;

    private final ObservationRegistry observations;

    GeminiTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    /** Opens the attempt's observation; the caller makes the request inside its scope. */
    Observation start(String slot, String model, int attempt) {
        return Observation.createNotStarted(OBSERVATION, observations)
                .lowCardinalityKeyValue("slot", slot)
                .lowCardinalityKeyValue("model", model)
                .highCardinalityKeyValue("attempt", String.valueOf(attempt))
                .start();
    }

    /** Closes the observation, records the timer and writes the log line. {@code detail} is appended as is. */
    void finish(Observation observation, String slot, String model, int attempt, String outcome, int status,
            long startedNanos, String detail) {
        long latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        observation.lowCardinalityKeyValue("outcome", outcome).stop();
        record(slot, model, attempt, outcome, status, latencyMillis, detail);
    }

    /** For a call that never reached the provider, such as a missing key. */
    void record(String slot, String model, int attempt, String outcome, int status, long latencyMillis,
            String detail) {
        Timer.builder("llm.requests")
                .description("Calls to the language model provider, by model and outcome")
                .tag("model", model)
                .tag("outcome", outcome)
                .register(meters)
                .record(latencyMillis, TimeUnit.MILLISECONDS);
        String line = "llm.request outcome={} status={} model={} slot={} attempt={} latency_ms={}{}";
        Object[] values = {outcome, status == 0 ? "none" : status, model, slot, attempt, latencyMillis, detail};
        switch (outcome) {
            case "answered", "blocked" -> LOG.info(line, values);
            case "config_error" -> LOG.error(line, values);
            default -> LOG.warn(line, values);
        }
    }
}
