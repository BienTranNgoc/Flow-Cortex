package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A workflow as the admin console lists it, with its run totals. {@code ownerEmail} is {@code null} once the
 * owner's row is gone; {@code lastRunAt} is {@code null} for a workflow never run.
 */
public record AdminWorkflowRow(
        UUID id, String name, UUID ownerId, String ownerEmail, String status, boolean shared, int steps, long runs,
        long runsLast7Days, Instant lastRunAt, Instant createdAt, Instant updatedAt) {
}
