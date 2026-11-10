package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowRun;

/** A run in a member's history with the name of the workflow it ran, read in the same statement. */
public record RunHistoryRow(WorkflowRun run, String workflowName) {
}
