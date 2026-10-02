package com.flowcortex.workflow.api;

import java.util.UUID;

/**
 * Public view of a {@code workflow_step} row. {@code mode} is {@code inline} or {@code remote}:
 * whether the step runs in the API or on the media engine, which decides what input a run needs.
 */
public record WorkflowStepView(UUID id, int stepOrder, String name, String type, String mode) {
}
