package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@code workflow} row (design.md D1) returned by {@link
 * WorkflowService}. {@code steps} is always populated in definition order.
 */
public record WorkflowView(
        UUID id, UUID ownerId, String name, boolean shared, Instant createdAt, Instant updatedAt,
        List<WorkflowStepView> steps) {
}
