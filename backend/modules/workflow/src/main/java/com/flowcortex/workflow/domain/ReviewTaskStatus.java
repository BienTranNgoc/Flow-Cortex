package com.flowcortex.workflow.domain;

/** A {@link ReviewTask}'s decision state. Persisted lowercase via {@link ReviewTaskStatusConverter}. */
public enum ReviewTaskStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** Nobody decided before the task's time limit; set only by the expiry sweep. */
    EXPIRED
}
