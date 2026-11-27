package com.flowcortex.workflow.application;

import java.util.UUID;

/** A batch changed in a way its next dispatch pass should see now: created, resumed, cancelled or retried. */
record BatchDispatchRequested(UUID batchId) {
}
