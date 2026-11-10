package com.flowcortex.workflow.api;

import java.time.Instant;

/**
 * One step of a run as a log line. {@code level} is {@code error}, {@code warn} or {@code info};
 * {@code message} is the step's error message for an error and {@code null} otherwise;
 * {@code durationMs} is {@code null} until the step has both a start and a finish.
 */
public record RunLogEntry(
        int stepOrder, String type, String level, String status, String message, String errorCode, int attempt,
        int deliveryCount, Instant startedAt, Instant finishedAt, Long durationMs) {
}
