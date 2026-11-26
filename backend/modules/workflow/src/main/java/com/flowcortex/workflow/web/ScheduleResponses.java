package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.schedule.ScheduleFireView;
import com.flowcortex.workflow.api.schedule.ScheduleView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Response bodies of the schedule routes. */
final class ScheduleResponses {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ScheduleResponses() {
    }

    /**
     * {@code pausedAt}, {@code nextRunAt} and {@code nextRunLocal} are mutually exclusive by status: an active
     * schedule has a next fire and no pause time, a paused one the reverse. {@code nextRunLocal} is {@code nextRunAt}
     * in the schedule's own zone, with its offset.
     */
    record ScheduleResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID workflowId,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"daily", "weekly", "monthly"})
            String frequency,
            @Schema(nullable = true, allowableValues = {"monday", "tuesday", "wednesday", "thursday", "friday",
                    "saturday", "sunday"}) String dayOfWeek,
            @Schema(nullable = true) Integer dayOfMonth,
            @Schema(requiredMode = RequiredMode.REQUIRED, example = "08:00") String time,
            @Schema(requiredMode = RequiredMode.REQUIRED) String timezone,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"active", "paused"}) String status,
            @Schema(nullable = true) Instant pausedAt,
            @Schema(nullable = true) Instant nextRunAt,
            @Schema(nullable = true, format = "date-time", example = "2026-11-17T08:00:00+07:00") String nextRunLocal,
            @Schema(nullable = true) String input,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean acknowledgeCost,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

        static ScheduleResponse from(ScheduleView view) {
            return new ScheduleResponse(view.id(), view.workflowId(), view.frequency().wire(),
                    view.dayOfWeek() == null ? null : view.dayOfWeek().name().toLowerCase(Locale.ROOT),
                    view.dayOfMonth(), TIME.format(view.time()), view.timezone(), view.status(), view.pausedAt(),
                    view.nextRunAt(),
                    view.nextRunLocal() == null ? null
                            : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(view.nextRunLocal()),
                    view.input(), view.acknowledgeCost(), view.createdAt(), view.updatedAt());
        }
    }

    /**
     * One fire. {@code runId} and {@code runStatus} are null when no run is reportable; {@code errorCode} and
     * {@code errorMessage} are set only for a {@code failed} fire, the message in the reader's language.
     */
    record ScheduleFireResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"manual", "scheduled"}) String trigger,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant scheduledAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"starting", "started", "failed"})
            String status,
            @Schema(nullable = true) UUID runId,
            @Schema(nullable = true) String runStatus,
            @Schema(nullable = true) String errorCode,
            @Schema(nullable = true) String errorMessage,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

        static ScheduleFireResponse from(ScheduleFireView view) {
            return new ScheduleFireResponse(view.id(), view.trigger(), view.scheduledAt(), view.status(),
                    view.runId(), view.runStatus(), view.errorCode(), view.errorMessage(), view.createdAt());
        }
    }
}
