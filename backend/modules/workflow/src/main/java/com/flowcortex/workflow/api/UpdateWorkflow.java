package com.flowcortex.workflow.api;

/**
 * A partial change to a workflow: a {@code null} field stays as it is. {@code status} is the wire value
 * {@code draft} or {@code published}. Visibility changes go through the sharing service, never here.
 */
public record UpdateWorkflow(String name, String status) {
}
