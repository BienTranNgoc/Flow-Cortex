package com.flowcortex.workflow.domain;

/**
 * A {@link WorkflowRun}'s overall status, derived from its {@link RunStep}s: {@code RUNNING}
 * while any step is non-terminal, {@code ERROR} the moment a step errors (halts dispatch),
 * {@code COMPLETED} once every step is {@code COMPLETED}. A member's stop request moves it to
 * {@code CANCELLING} while a step is still in flight, then to the terminal {@code CANCELLED}.
 * Persisted lowercase via {@link RunStatusConverter}.
 */
public enum RunStatus {
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    ERROR,
    CANCELLING,
    CANCELLED
}
