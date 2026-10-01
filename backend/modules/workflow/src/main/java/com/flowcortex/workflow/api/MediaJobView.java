package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A remote run step as the admin console lists it; {@code jobId} is the step id the job carries.
 * The deadlines are what the reaper closes a {@code running} step on: {@code queueDeadline} until
 * it is picked up, then {@code leaseUntil} and {@code runDeadline}. Rows older than the lease
 * columns hold {@code null} there.
 */
public record MediaJobView(
        UUID jobId, UUID runId, int stepOrder, String capability, String status, int attempt, int deliveryCount,
        Instant pickedUpAt, Instant queueDeadline, Instant leaseUntil, Instant runDeadline, Integer progressPercent,
        String errorCode, String errorMessage, Instant startedAt, Instant finishedAt) {
}
