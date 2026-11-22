package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.RunStatus;
import java.time.Instant;
import java.util.UUID;

/** The columns of one run a usage summary needs, deleted runs included. */
public record RunUsageRow(UUID runId, UUID workflowId, String workflowName, RunStatus status, Instant startedAt) {
}
