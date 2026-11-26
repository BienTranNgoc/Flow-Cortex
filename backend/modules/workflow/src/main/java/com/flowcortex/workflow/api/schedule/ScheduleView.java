package com.flowcortex.workflow.api.schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A member's schedule. {@code status} is {@code active} or {@code paused}; {@code nextRunAt} and
 * {@code nextRunLocal} (the same instant in the schedule's zone) are {@code null} while paused, and
 * {@code pausedAt} is {@code null} while active.
 */
public record ScheduleView(
        UUID id, UUID workflowId, ScheduleFrequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth,
        LocalTime time, String timezone, String status, Instant pausedAt, Instant nextRunAt,
        OffsetDateTime nextRunLocal, String input, boolean acknowledgeCost, Instant createdAt, Instant updatedAt) {
}
