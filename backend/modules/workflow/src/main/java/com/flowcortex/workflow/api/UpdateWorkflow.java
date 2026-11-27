package com.flowcortex.workflow.api;

/**
 * A partial change to a workflow: a {@code null} field stays as it is. {@code status} is the wire value
 * {@code draft} or {@code published}. {@code generateInsight} turns the model-written insight of a table result on or
 * off. Visibility changes go through the sharing service, never here.
 */
public record UpdateWorkflow(String name, String status, Boolean allowFork, Boolean generateInsight) {

    /** A change that leaves the fork switch and the insight switch as they are. */
    public UpdateWorkflow(String name, String status) {
        this(name, status, null, null);
    }

    /** A change that leaves the insight switch as it is. */
    public UpdateWorkflow(String name, String status, Boolean allowFork) {
        this(name, status, allowFork, null);
    }
}
