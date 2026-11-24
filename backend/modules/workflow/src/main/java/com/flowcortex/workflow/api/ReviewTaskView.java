package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A review task as its owner reads it. {@code pickOptions}, {@code questions} and {@code answers}
 * are null when the task has none; {@code questions} is already worded in the reader's language.
 */
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
    Instant createdAt,
    Long version,
    UUID currentRevisionId,
    UUID approvedRevisionId,
    Instant expiresAt,
    List<Map<String, Object>> pickOptions,
    List<Question> questions,
    String questionsState,
    Map<String, Object> answers
) {

    /** A QA review question; {@code options} is empty for a {@code text} question. */
    public record Question(String id, String type, String question, List<String> options) {}
}
