package com.flowcortex.workflow.domain;

/**
 * Staff moderation state of a workflow, independent of its owner-chosen visibility. Only a
 * {@code VISIBLE} workflow reaches anyone but its owner; {@code HIDDEN} and {@code REMOVED} have the same reach.
 */
public enum WorkflowModerationStatus {
    VISIBLE,
    HIDDEN,
    REMOVED
}
