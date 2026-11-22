package com.flowcortex.workflow.api.batch;

import java.time.Instant;
import java.util.UUID;

/**
 * One batch with the count of its rows in each status. {@code status} is the lowercase wire value
 * ({@code running}, {@code paused}, {@code completed}, {@code completed_with_errors}, {@code cancelled}); a row
 * whose run is not started yet counts as pending. {@code pauseReason}, {@code cancelReason}, {@code parentBatchId}
 * and {@code finishedAt} are {@code null} while they do not apply.
 */
public record BatchView(
        UUID id, UUID workflowId, String workflowName, String name, String status, int concurrency, int totalRows,
        long pendingRows, long runningRows, long completedRows, long failedRows, long cancelledRows,
        String pauseReason, String cancelReason, UUID parentBatchId, Instant createdAt, Instant finishedAt) {
}
