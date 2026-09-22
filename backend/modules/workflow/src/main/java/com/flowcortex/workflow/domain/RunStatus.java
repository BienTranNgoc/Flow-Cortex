package com.flowcortex.workflow.domain;

/**
 * A {@link WorkflowRun}'s overall status (design.md D1/D5, R-044) — derived
 * from its {@link RunStep}s, never set directly by a caller: {@code RUNNING}
 * while any step is non-terminal, {@code ERROR} the moment a step errors
 * (halt — no later dispatch), {@code COMPLETED} once every step is
 * {@code COMPLETED}.
 *
 * <p>Persisted lowercase ({@code running}|{@code completed}|{@code error},
 * design.md D1) via {@link RunStatusConverter} — the enum constants stay
 * upper-case, matching this codebase's Java naming convention ({@link
 * com.flowcortex.user.domain.Role}, {@link com.flowcortex.user.domain.UserStatus}).
 */
public enum RunStatus {
    RUNNING,
    COMPLETED,
    ERROR
}
