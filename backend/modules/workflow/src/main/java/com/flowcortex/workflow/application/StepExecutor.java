package com.flowcortex.workflow.application;

import java.time.Duration;

/**
 * Runs one step type. An {@link Mode#INLINE} executor does the work on an application thread
 * and returns the step's output; a {@link Mode#REMOTE} executor only hands the step to the media
 * engine, inside the claim transaction, and returns {@code null} — its result arrives as an event.
 * Either signals a step failure with a code by throwing {@link StepFailedException}.
 */
interface StepExecutor {

    enum Mode {
        INLINE,
        REMOTE
    }

    enum InputPolicy {
        RESOLVED,
        STORED,
        STORED_WITH_OUTPUTS
    }

    String type();

    Mode mode();

    /** Stored policies are reserved for media assets snapshotted when the run starts. */
    default InputPolicy inputPolicy() {
        return InputPolicy.RESOLVED;
    }

    default InputPolicy inputPolicy(String config) {
        return inputPolicy();
    }

    /** How long one run of this step may take once picked up; bounds its run deadline. */
    Duration maxRuntime();

    /** How long a claimed step may wait for pickup. An inline step is picked up by a local thread, so the lease bounds it. */
    default Duration queueTimeout(Duration lease) {
        return lease;
    }

    /** Whether this step type may run now; a workflow with a step that may not is refused at start. */
    default boolean available() {
        return true;
    }

    String execute(StepContext context);
}
