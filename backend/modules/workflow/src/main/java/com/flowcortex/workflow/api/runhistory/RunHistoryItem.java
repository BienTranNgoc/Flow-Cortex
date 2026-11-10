package com.flowcortex.workflow.api.runhistory;

import java.time.Instant;
import java.util.UUID;

/**
 * One run of a member's history. {@code status} is the lowercase wire value; {@code durationSeconds} is
 * {@code null} until the run finishes and {@code creditsCharged} while credits are off.
 */
public record RunHistoryItem(
        UUID id, UUID workflowId, String workflowName, String title, String status, boolean favorite,
        Instant startedAt, Instant finishedAt, Long durationSeconds, Long creditsCharged, String errorMessage) {
}
