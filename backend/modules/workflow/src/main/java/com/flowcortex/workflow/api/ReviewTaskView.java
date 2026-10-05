package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

public record ReviewTaskView(
    UUID id,
    UUID runId,
    UUID runStepId,
    String reviewType,
    String status,
    String originalPayload,
    String reviewedPayload,
    UUID reviewedBy,
    Instant reviewedAt,
    Instant createdAt
) {}
