package com.flowcortex.workflow.api.schedule;

import java.time.Instant;
import java.util.UUID;

/**
 * One fire of a schedule as reported to its owner. {@code trigger} is {@code manual} or {@code scheduled};
 * {@code status} is {@code starting}, {@code started} or {@code failed}. {@code runId} and {@code runStatus}
 * are {@code null} when no run is reportable (none, or deleted by the member). {@code errorCode} and
 * {@code errorMessage} are set only for a failed fire, the message in the reader's language.
 */
public record ScheduleFireView(
        UUID id, String trigger, Instant scheduledAt, String status, UUID runId, String runStatus, String errorCode,
        String errorMessage, Instant createdAt) {
}
