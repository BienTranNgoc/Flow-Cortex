package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A run as the admin console lists it. {@code ownerEmail} is {@code null} once the owner's row is
 * gone; {@code errorMessage} is the engine's own text; {@code creditsCharged} is {@code null} while
 * credits are switched off.
 */
public record AdminRunView(
        UUID id, UUID workflowId, String workflowName, UUID ownerId, String ownerEmail, String status,
        String errorMessage, Instant startedAt, Instant finishedAt, Long creditsCharged) {
}
