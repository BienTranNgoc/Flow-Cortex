package com.flowcortex.workflow.api;

import java.time.Instant;

/**
 * A step as staff see it: what ran and how it ended, never what it was given or produced.
 * {@code mode} is {@code inline} or {@code remote}.
 */
public record AdminRunStepView(
        int stepOrder, String type, String mode, String status, int attempt, int deliveryCount,
        Integer progressPercent, String errorCode, String errorMessage, Instant pickedUpAt, Instant startedAt,
        Instant finishedAt) {
}
