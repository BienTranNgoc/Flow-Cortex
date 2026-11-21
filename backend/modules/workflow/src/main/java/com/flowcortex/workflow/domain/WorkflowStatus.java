package com.flowcortex.workflow.domain;

/** Lifecycle of a workflow definition: only a published one can be shared with other members. */
public enum WorkflowStatus {
    DRAFT,
    PUBLISHED
}
