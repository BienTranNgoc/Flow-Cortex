package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.MediaJobView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A remote step as a job: {@code jobId} is the id the worker's messages and logs carry, {@code
 * deliveryCount} counts redeliveries (0 for the first). A {@code running} job with no {@code
 * pickedUpAt} is still queued until {@code queueDeadline}; once picked up it is held by {@code
 * leaseUntil} and {@code runDeadline}. Older rows hold {@code null} deadlines.
 */
record MediaJobResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID jobId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID runId,
        @Schema(requiredMode = RequiredMode.REQUIRED) int stepOrder,
        @Schema(requiredMode = RequiredMode.REQUIRED) String capability,
        @Schema(requiredMode = RequiredMode.REQUIRED) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) int attempt,
        @Schema(requiredMode = RequiredMode.REQUIRED) int deliveryCount,
        Instant pickedUpAt,
        Instant queueDeadline,
        Instant leaseUntil,
        Instant runDeadline,
        Integer progressPercent,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt) {

    static MediaJobResponse from(MediaJobView view) {
        return new MediaJobResponse(view.jobId(), view.runId(), view.stepOrder(), view.capability(), view.status(),
                view.attempt(), view.deliveryCount(), view.pickedUpAt(), view.queueDeadline(), view.leaseUntil(),
                view.runDeadline(), view.progressPercent(), view.errorCode(), view.errorMessage(), view.startedAt(),
                view.finishedAt());
    }
}
