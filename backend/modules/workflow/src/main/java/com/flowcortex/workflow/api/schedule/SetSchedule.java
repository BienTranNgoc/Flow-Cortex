package com.flowcortex.workflow.api.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * What a member sets for a workflow. {@code dayOfWeek} is read only for a weekly schedule and
 * {@code dayOfMonth} (1 to 28) only for a monthly one; the other is stored empty. {@code timezone} is a
 * region id or {@code UTC}.
 */
public record SetSchedule(ScheduleFrequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth, LocalTime time,
        String timezone, boolean active, String input, boolean acknowledgeCost) {
}
