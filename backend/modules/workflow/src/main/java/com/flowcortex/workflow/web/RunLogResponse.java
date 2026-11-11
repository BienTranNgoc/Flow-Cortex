package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunLogEntry;
import com.flowcortex.workflow.api.RunLogSummary;
import com.flowcortex.workflow.api.RunLogView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;

/**
 * A run's step log. {@code traceId} is {@code null} for a run started before trace ids were stored; entries are
 * ordered error, warn, info and then by step order.
 */
record RunLogResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String status,
        @Schema(nullable = true) String traceId,
        @Schema(requiredMode = RequiredMode.REQUIRED) int stepCount,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Entry> entries,
        @Schema(requiredMode = RequiredMode.REQUIRED) Summary summary) {

    static RunLogResponse from(RunLogView view) {
        return new RunLogResponse(view.status(), view.traceId(), view.stepCount(),
                view.entries().stream().map(Entry::from).toList(), Summary.from(view.summary()));
    }

    /** One step as a log line; {@code message} is set only for an {@code error}. */
    record Entry(
            @Schema(requiredMode = RequiredMode.REQUIRED) int stepOrder,
            @Schema(requiredMode = RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"error", "warn", "info"}) String level,
            @Schema(requiredMode = RequiredMode.REQUIRED) String status,
            @Schema(nullable = true) String message,
            @Schema(nullable = true) String errorCode,
            @Schema(requiredMode = RequiredMode.REQUIRED) int attempt,
            @Schema(requiredMode = RequiredMode.REQUIRED) int deliveryCount,
            @Schema(nullable = true) Instant startedAt,
            @Schema(nullable = true) Instant finishedAt,
            @Schema(nullable = true) Long durationMs) {

        static Entry from(RunLogEntry entry) {
            return new Entry(entry.stepOrder(), entry.type(), entry.level(), entry.status(), entry.message(),
                    entry.errorCode(), entry.attempt(), entry.deliveryCount(), entry.startedAt(), entry.finishedAt(),
                    entry.durationMs());
        }
    }

    /** Counts over every step of the run, whatever level or limit the log was read with. */
    record Summary(
            @Schema(requiredMode = RequiredMode.REQUIRED) int errorCount,
            @Schema(requiredMode = RequiredMode.REQUIRED) int warningCount,
            @Schema(nullable = true) String firstError) {

        static Summary from(RunLogSummary summary) {
            return new Summary(summary.errorCount(), summary.warningCount(), summary.firstError());
        }
    }
}
