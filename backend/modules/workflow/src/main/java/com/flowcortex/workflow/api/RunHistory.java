package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read-only view of what a member has already run, for modules that must not touch workflow tables. */
public interface RunHistory {

    /** True when the member has a {@code completed} run on any workflow, including a deleted one. */
    boolean hasCompletedRun(UUID ownerId);

    /** The member's earliest {@code completed} run (by start time, then id), including one of a deleted workflow. */
    Optional<CompletedRunRef> earliestCompletedRun(UUID ownerId);

    /** Identity of a completed run; {@code finishedAt} is {@code null} when the run was never stamped. */
    record CompletedRunRef(UUID runId, UUID workflowId, Instant finishedAt) {
    }
}
