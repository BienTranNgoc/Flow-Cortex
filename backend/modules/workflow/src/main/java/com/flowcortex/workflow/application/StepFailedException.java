package com.flowcortex.workflow.application;

/** A step failure the engine records as-is: {@code code} goes to {@code run_step.error_code} (nullable). */
class StepFailedException extends RuntimeException {

    private final String code;

    StepFailedException(String code, String message) {
        super(message);
        this.code = code;
    }

    StepFailedException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
