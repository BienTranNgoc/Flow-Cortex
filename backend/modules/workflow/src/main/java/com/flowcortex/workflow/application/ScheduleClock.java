package com.flowcortex.workflow.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;

/**
 * When a schedule fires next, as wall-clock arithmetic in the schedule's own zone, so a daily 08:00
 * stays 08:00 across the zone's offset changes. Public because the request validator in the web
 * package asks it which zones it accepts.
 */
public final class ScheduleClock {

    public static final String DAILY = "daily";
    public static final String WEEKLY = "weekly";
    public static final String MONTHLY = "monthly";

    private ScheduleClock() {}

    /**
     * The first instant strictly after {@code now} whose local date and time in {@code zone} match the rule. A
     * local time the zone skips shifts forward by the gap; one it repeats takes the earlier instant.
     *
     * @param dayOfWeek ISO day number 1 (Monday) to 7 (Sunday), required for {@code weekly}
     * @param dayOfMonth 1 to 28, required for {@code monthly}
     * @throws IllegalArgumentException for an unknown frequency or a missing day the frequency needs
     */
    public static Instant next(String frequency, Integer dayOfWeek, Integer dayOfMonth, LocalTime time, ZoneId zone,
            Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate date = switch (frequency == null ? "" : frequency) {
            case DAILY -> today;
            case WEEKLY -> today.with(ChronoField.DAY_OF_WEEK, required(dayOfWeek, "dayOfWeek"));
            case MONTHLY -> today.withDayOfMonth(required(dayOfMonth, "dayOfMonth"));
            default -> throw new IllegalArgumentException("Unknown schedule frequency: " + frequency);
        };
        Instant candidate = resolve(date, time, zone);
        if (candidate.isAfter(now)) {
            return candidate;
        }
        LocalDate following = switch (frequency) {
            case DAILY -> date.plusDays(1);
            case WEEKLY -> date.plusWeeks(1);
            default -> date.plusMonths(1);
        };
        return resolve(following, time, zone);
    }

    /** The instant written as the local date and time of {@code zone}, with its offset. */
    public static OffsetDateTime local(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toOffsetDateTime();
    }

    /**
     * Only region ids the JVM knows, or {@code UTC}: a fixed offset such as {@code +07:00} ignores the rule
     * changes of the region it stands for.
     *
     * @throws IllegalArgumentException for anything else, including null
     */
    public static ZoneId parseZone(String zone) {
        if (zone == null || !(zone.equals("UTC") || ZoneId.getAvailableZoneIds().contains(zone))) {
            throw new IllegalArgumentException("Unsupported time zone: " + zone);
        }
        return ZoneId.of(zone);
    }

    private static Instant resolve(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toInstant();
    }

    private static int required(Integer day, String name) {
        if (day == null) {
            throw new IllegalArgumentException(name + " is required for this frequency");
        }
        return day;
    }
}
