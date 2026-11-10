package com.flowcortex.workflow.api;

/** A version with its snapshot. */
public record WorkflowVersionDetailView(WorkflowVersionView version, VersionSnapshotView snapshot) {
}
