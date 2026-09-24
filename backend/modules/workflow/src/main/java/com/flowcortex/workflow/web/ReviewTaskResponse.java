package com.flowcortex.workflow.web;

import com.flowcortex.workflow.domain.ReviewTask;
import java.time.Instant;
import java.util.UUID;

public record ReviewTaskResponse(
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
) {
    public static ReviewTaskResponse from(ReviewTask task) {
        return new ReviewTaskResponse(
            task.getId(),
            task.getRunId(),
            task.getRunStepId(),
            task.getReviewType(),
            task.getStatus(),
            task.getOriginalPayload(),
            task.getReviewedPayload(),
            task.getReviewedBy(),
            task.getReviewedAt(),
            task.getCreatedAt()
        );
    }
}
