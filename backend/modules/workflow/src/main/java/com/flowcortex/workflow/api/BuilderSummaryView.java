package com.flowcortex.workflow.api;

/**
 * A builder's totals: the workflows they own (not deleted) and every run of those workflows, whoever
 * started it.
 */
public record BuilderSummaryView(long totalWorkflows, long publishedWorkflows, long totalRuns) {
}
