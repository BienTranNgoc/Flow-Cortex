package com.flowcortex.workflow.application;

import java.util.UUID;

/**
 * A run started by a {@code workflow.call} step reached a final status. Published inside the
 * transaction that wrote the status, so the waiting parent step hears of it after commit.
 */
record ChildRunFinishedEvent(UUID childRunId) {
}
