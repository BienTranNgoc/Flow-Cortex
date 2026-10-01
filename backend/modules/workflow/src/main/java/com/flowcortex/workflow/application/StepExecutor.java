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

    String type();

    Mode mode();

    /** How long one run of this step may take once picked up; bounds its run deadline. */
    Duration maxRuntime();

    /** How long a claimed step may wait for pickup. An inline step is picked up by a local thread, so the lease bounds it. */
    default Duration queueTimeout(Duration lease) {
        return lease;
    }

    String execute(StepContext context);
}
