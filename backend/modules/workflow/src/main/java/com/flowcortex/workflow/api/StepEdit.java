package com.flowcortex.workflow.api;

/**
 * A partial change to a step: a {@code null} field stays as it is, and an empty {@code name} or {@code inputRef}
 * clears it. The type and the position of a step cannot change.
 */
public record StepEdit(String name, String config, String inputRef) {
}
