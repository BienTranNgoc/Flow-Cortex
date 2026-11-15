package com.flowcortex.workflow.api;

import java.util.UUID;

/** Reads the step graph of a workflow the caller can see. */
public interface WorkflowGraphs {

    /** The graph of {@code workflowId}; throws a 404 when the workflow is not visible to the caller. */
    WorkflowGraphView graphOf(UUID callerId, UUID workflowId);
}
