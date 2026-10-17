package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.ReviewTaskView;
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
    Instant createdAt,
    Long version,
    UUID currentRevisionId,
    UUID approvedRevisionId
) {
    public static ReviewTaskResponse from(ReviewTaskView view) {
        return new ReviewTaskResponse(
            view.id(),
            view.runId(),
            view.runStepId(),
            view.reviewType(),
            view.status(),
            view.originalPayload(),
            view.reviewedPayload(),
            view.reviewedBy(),
            view.reviewedAt(),
            view.createdAt(),
            view.version(),
            view.currentRevisionId(),
            view.approvedRevisionId()
        );
    }
}
