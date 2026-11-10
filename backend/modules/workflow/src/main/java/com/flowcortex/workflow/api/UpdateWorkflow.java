package com.flowcortex.workflow.api;

/**
 * A partial change to a workflow: a {@code null} field stays as it is. {@code status} is the wire value
 * {@code draft} or {@code published}.
 */
public record UpdateWorkflow(String name, Boolean shared, String status, Boolean allowFork) {

    /** A change that leaves the fork switch as it is. */
    public UpdateWorkflow(String name, Boolean shared, String status) {
        this(name, shared, status, null);
    }
}
