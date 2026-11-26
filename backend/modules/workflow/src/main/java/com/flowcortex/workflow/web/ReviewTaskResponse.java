package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.ReviewTaskView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    UUID approvedRevisionId,
    @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt,
    List<Map<String, Object>> pickOptions,
    List<ReviewQuestion> questions,
    String questionsState,
    Map<String, Object> answers
) {

    /** A QA review question; {@code type} is {@code single}, {@code multi} or {@code text}. */
    public record ReviewQuestion(
        @Schema(requiredMode = RequiredMode.REQUIRED) String id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = RequiredMode.REQUIRED) String question,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<String> options
    ) {}

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
            view.approvedRevisionId(),
            view.expiresAt(),
            view.pickOptions(),
            view.questions() == null ? null : view.questions().stream()
                .map(q -> new ReviewQuestion(q.id(), q.type(), q.question(), q.options()))
                .toList(),
            view.questionsState(),
            view.answers()
        );
    }
}
