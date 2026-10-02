package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.WorkflowRun;

/** A run with the name of the workflow it ran, read in the same statement. */
public record RunWithWorkflowName(WorkflowRun run, String workflowName) {
}
