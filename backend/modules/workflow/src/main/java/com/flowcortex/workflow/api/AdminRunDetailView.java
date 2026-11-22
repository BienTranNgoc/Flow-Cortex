package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One run with its steps for staff. It carries no input, output or stored outputs of the run or its
 * steps: those are the member's content. {@code creditsCharged} is {@code null} while credits are off.
 */
public record AdminRunDetailView(
        UUID id, UUID workflowId, String workflowName, UUID ownerId, String ownerEmail, String status,
        String errorMessage, Instant startedAt, Instant finishedAt, Long creditsCharged,
        List<AdminRunStepView> steps) {
}
