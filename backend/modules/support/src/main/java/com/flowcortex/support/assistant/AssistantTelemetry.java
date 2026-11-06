package com.flowcortex.support.assistant;

import com.flowcortex.llm.api.LlmJsonResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One counter tick and one log line per question, plus the provider's model and token counts on the
 * request's span. Never the question text, the answer or anything naming the asker.
 */
@Component
class AssistantTelemetry {

    static final String OUTCOME_COUNTER = "support.assistant.outcome";

    private static final Logger LOG = LoggerFactory.getLogger(AssistantTelemetry.class);

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    AssistantTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    void record(String outcome, int sources, int questionLength, long startedNanos) {
        meters.counter(OUTCOME_COUNTER, "outcome", outcome).increment();
        LOG.info("support.assistant outcome={} sources={} length={} latency_ms={}", outcome, sources, questionLength,
                (System.nanoTime() - startedNanos) / 1_000_000);
    }

    /** Tags the current request span; high-cardinality, so the values never become metric tags. */
    void provider(String model, LlmJsonResult result, int sources) {
        Observation current = observations.getCurrentObservation();
        if (current == null) {
            return;
        }
        current.highCardinalityKeyValue("gen_ai.system", "gcp.gemini")
                .highCardinalityKeyValue("gen_ai.request.model", model)
                .highCardinalityKeyValue("support.assistant.sources", Integer.toString(sources));
        if (result instanceof LlmJsonResult.Answered answered) {
            current.highCardinalityKeyValue("gen_ai.usage.input_tokens", Integer.toString(answered.inputTokens()))
                    .highCardinalityKeyValue("gen_ai.usage.output_tokens", Integer.toString(answered.outputTokens()));
        }
    }
}
