package com.flowcortex.workflow.domain;

/**
 * A {@link RunStep}'s status ladder. Only legal transitions: {@code PENDING -> RUNNING} (atomic
 * single-active claim), {@code RUNNING -> COMPLETED}, {@code RUNNING -> ERROR} (halts the run).
 * All others are rejected by {@link RunStep}'s mutators. {@code SKIPPED} marks a step a cancelled
 * run never started. Persisted lowercase via {@link RunStepStatusConverter}.
 */
public enum RunStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR,
    SKIPPED
}
