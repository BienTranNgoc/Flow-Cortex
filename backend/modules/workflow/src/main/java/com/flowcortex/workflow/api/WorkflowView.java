package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@code workflow} row returned by {@link WorkflowService}.
 * {@code steps} is always populated in definition order. {@code available} is false while a step
 * type it uses is switched off; starting a run of it is then refused.
 */
public record WorkflowView(
        UUID id, UUID ownerId, String name, boolean shared, Instant createdAt, Instant updatedAt,
        List<WorkflowStepView> steps, boolean available) {
}
