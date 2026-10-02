package com.flowcortex.workflow.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Puts a run's and a step's identifiers on every log line written while it is open. Closing it
 * restores what the keys held before, so contexts nest: an advance inside a result handler keeps
 * the handler's {@code runId} once it returns.
 */
final class StepLogContext implements AutoCloseable {

    static final String RUN_ID = "runId";
    static final String RUN_STEP_ID = "runStepId";
    static final String JOB_ID = "jobId";
    static final String ATTEMPT = "attempt";
    static final String DELIVERY_COUNT = "deliveryCount";

    private final Map<String, String> previous = new LinkedHashMap<>();

    private StepLogContext() {
    }

    static StepLogContext run(UUID runId) {
        return new StepLogContext().put(RUN_ID, runId);
    }

    /** A remote step's job id is its step id; an inline step has no job. Null values are left out. */
    static StepLogContext step(UUID runId, UUID stepId, boolean remote, Number attempt, Number deliveryCount) {
        return new StepLogContext()
                .put(RUN_ID, runId)
                .put(RUN_STEP_ID, stepId)
                .put(JOB_ID, remote ? stepId : null)
                .put(ATTEMPT, attempt)
                .put(DELIVERY_COUNT, deliveryCount);
    }

    private StepLogContext put(String key, Object value) {
        if (value != null) {
            previous.putIfAbsent(key, MDC.get(key));
            MDC.put(key, value.toString());
        }
        return this;
    }

    @Override
    public void close() {
        previous.forEach((key, value) -> {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        });
    }
}
