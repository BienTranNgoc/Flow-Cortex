package com.flowcortex.workflow.api;

/**
 * Narrows the admin workflow list. A {@code null} field does not filter; {@code q} is matched literally
 * (case-insensitive) inside the workflow name.
 */
public record AdminWorkflowFilter(String status, String q) {
}
