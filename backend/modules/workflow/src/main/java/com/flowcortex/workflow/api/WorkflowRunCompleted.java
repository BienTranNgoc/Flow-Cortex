package com.flowcortex.workflow.api;

import java.util.UUID;

/**
 * A run reached {@code completed}. Published in the transaction that completes the run, at most
 * once per completion, and heard after commit.
 */
public record WorkflowRunCompleted(UUID runId, UUID ownerId) {
}
