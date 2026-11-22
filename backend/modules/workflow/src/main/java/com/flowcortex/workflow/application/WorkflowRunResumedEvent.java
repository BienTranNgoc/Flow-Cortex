package com.flowcortex.workflow.application;

import java.util.UUID;

/**
 * Published in the transaction that re-opened or re-armed a run's step; {@link RunAdvancer} advances
 * the run once it commits, as it does for a start.
 */
record WorkflowRunResumedEvent(UUID runId) {
}
