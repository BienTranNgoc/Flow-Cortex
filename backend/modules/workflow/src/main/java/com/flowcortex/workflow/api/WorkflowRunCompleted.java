package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A run reached {@code completed}. Published in the transaction that completes the run, so it is
 * heard only after that transaction commits and at most once per completion.
 */
public record WorkflowRunCompleted(UUID runId, UUID ownerId, Instant finishedAt) {
}
