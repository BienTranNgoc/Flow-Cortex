package com.flowcortex.workflow.domain;

/**
 * A {@link RunStep}'s status ladder (design.md D5, R-043) — the ONLY legal
 * transitions are {@code PENDING -> RUNNING} (the atomic single-active claim,
 * R-045), {@code RUNNING -> COMPLETED} (step work succeeds), and
 * {@code RUNNING -> ERROR} (step work throws, halting the run). Every other
 * transition is illegal and rejected by {@link RunStep}'s mutator methods —
 * there is no terminal-to-anything or pending-to-terminal edge.
 *
 * <p>Persisted lowercase ({@code pending}|{@code running}|{@code completed}|
 * {@code error}, design.md D1) via {@link RunStepStatusConverter}.
 */
public enum RunStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    ERROR
}
