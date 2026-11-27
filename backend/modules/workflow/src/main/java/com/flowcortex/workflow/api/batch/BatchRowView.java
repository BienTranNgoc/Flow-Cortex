package com.flowcortex.workflow.api.batch;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a batch. {@code status} is {@code pending}, {@code running}, {@code completed}, {@code failed} or
 * {@code cancelled}. {@code runId} is {@code null} until a run starts and after that run is removed;
 * {@code sourceRowIndex} is the index in the parent batch, set on a retry batch's rows only.
 */
public record BatchRowView(
        int rowIndex, Integer sourceRowIndex, String input, String status, UUID runId, String errorCode,
        String errorMessage, Instant startedAt, Instant finishedAt) {
}
