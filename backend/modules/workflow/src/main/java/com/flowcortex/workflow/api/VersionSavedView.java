package com.flowcortex.workflow.api;

/** The version a save, rollback or hotfix answers with; {@code deduplicated} is true when nothing new was written. */
public record VersionSavedView(WorkflowVersionView version, boolean deduplicated) {
}
