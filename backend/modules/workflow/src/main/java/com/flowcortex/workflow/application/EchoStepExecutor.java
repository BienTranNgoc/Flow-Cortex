package com.flowcortex.workflow.application;

import java.time.Duration;

/** {@code echo} — returns the resolved input verbatim; proves the engine without a provider. */
class EchoStepExecutor implements StepExecutor {

    @Override
    public String type() {
        return "echo";
    }

    @Override
    public Mode mode() {
        return Mode.INLINE;
    }

    @Override
    public Duration maxRuntime() {
        return FailStepExecutor.INLINE_MAX_RUNTIME;
    }

    @Override
    public String execute(StepContext context) {
        return context.input();
    }
}
