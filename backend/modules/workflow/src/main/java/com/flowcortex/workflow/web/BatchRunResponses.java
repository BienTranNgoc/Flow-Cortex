package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.batch.BatchRowView;
import com.flowcortex.workflow.api.batch.BatchView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** The wire shapes of the batch endpoints. */
final class BatchRunResponses {

    private BatchRunResponses() {
    }

    /**
     * {@code status} is the lowercase wire value; the five row counts add up to {@code totalRows} and a row
     * whose run is not started yet counts as pending. The reasons, {@code parentBatchId} and {@code finishedAt}
     * are null while they do not apply.
     */
    record BatchResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID workflowId,
            @Schema(requiredMode = RequiredMode.REQUIRED) String workflowName,
            String name,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"running", "paused", "completed",
                    "completed_with_errors", "cancelled"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) int concurrency,
            @Schema(requiredMode = RequiredMode.REQUIRED) int totalRows,
            @Schema(requiredMode = RequiredMode.REQUIRED) long pendingRows,
            @Schema(requiredMode = RequiredMode.REQUIRED) long runningRows,
            @Schema(requiredMode = RequiredMode.REQUIRED) long completedRows,
            @Schema(requiredMode = RequiredMode.REQUIRED) long failedRows,
            @Schema(requiredMode = RequiredMode.REQUIRED) long cancelledRows,
            @Schema(allowableValues = {"member", "insufficientCredits", "unavailable"}) String pauseReason,
            @Schema(allowableValues = {"member", "workflowUnavailable", "ownerInactive"}) String cancelReason,
            UUID parentBatchId,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            Instant finishedAt) {

        static BatchResponse from(BatchView view) {
            return new BatchResponse(view.id(), view.workflowId(), view.workflowName(), view.name(), view.status(),
                    view.concurrency(), view.totalRows(), view.pendingRows(), view.runningRows(),
                    view.completedRows(), view.failedRows(), view.cancelledRows(), view.pauseReason(),
                    view.cancelReason(), view.parentBatchId(), view.createdAt(), view.finishedAt());
        }
    }

    /**
     * {@code runId} is null until a run starts and after that run is removed from the database;
     * {@code sourceRowIndex} is the row's index in the parent batch, set on a retry batch's rows only.
     */
    record BatchRowResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) int rowIndex,
            Integer sourceRowIndex,
            @Schema(requiredMode = RequiredMode.REQUIRED) String input,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"pending", "running", "completed",
                    "failed", "cancelled"}) String status,
            UUID runId, String errorCode, String errorMessage, Instant startedAt, Instant finishedAt) {

        static BatchRowResponse from(BatchRowView view) {
            return new BatchRowResponse(view.rowIndex(), view.sourceRowIndex(), view.input(), view.status(),
                    view.runId(), view.errorCode(), view.errorMessage(), view.startedAt(), view.finishedAt());
        }
    }
}
