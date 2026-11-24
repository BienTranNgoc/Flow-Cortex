package com.flowcortex.workflow.application;

import java.util.UUID;

/**
 * Published in the transaction that re-armed one item of a completed iterate step; {@link RunAdvancer}
 * dispatches that item once the transaction commits.
 */
record IterateChildRegenerated(UUID runId, UUID childId) {
}
