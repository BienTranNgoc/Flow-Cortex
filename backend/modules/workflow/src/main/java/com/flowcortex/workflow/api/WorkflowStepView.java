package com.flowcortex.workflow.api;

import java.util.UUID;

/** Public view of a {@code workflow_step} row (design.md D1). */
public record WorkflowStepView(UUID id, int stepOrder, String name, String type) {
}
