package com.flowcortex.workflow.api.runhistory;

import java.util.UUID;

/** One workflow's share of the window; the credit figures are {@code null} while credits are off. */
public record TopWorkflowView(UUID workflowId, String name, long runs, Long credits, Double averageCredits) {
}
