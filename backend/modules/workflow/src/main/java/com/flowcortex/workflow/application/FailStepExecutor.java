package com.flowcortex.workflow.application;

import java.time.Duration;

/** {@code fail} — deterministically throws; exercises the run-error UI from a demo. */
class FailStepExecutor implements StepExecutor {

    /** The run-time bound of the inline demo steps, which finish in well under a second. */
    static final Duration INLINE_MAX_RUNTIME = Duration.ofSeconds(60);

    @Override
    public String type() {
        return "fail";
    }

    @Override
    public Mode mode() {
        return Mode.INLINE;
    }

    @Override
    public Duration maxRuntime() {
        return INLINE_MAX_RUNTIME;
    }

    @Override
    public String execute(StepContext context) {
        throw new IllegalStateException("Simulated step failure for demo purposes");
    }
}
