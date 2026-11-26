package com.flowcortex.billing.entitlement;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The calendar of a paid plan period: how long a cycle lasts, where a purchase's period starts and when it
 * renews. Pure and in UTC, so the stored instants never depend on a server's zone.
 */
public final class PlanPeriods {

    private static final int MONTHS_PER_YEAR = 12;
    private static final int AUTO_DEBIT_GRACE_DAYS = 7;

    private PlanPeriods() {
    }

    /** The period a purchase bought: it starts at {@code startsAt} and renews at {@code renewsAt}. */
    public record Period(Instant startsAt, Instant renewsAt) {
    }

    /** Months in a billing cycle: {@code monthly} 1, {@code yearly} 12. */
    public static int monthsOf(String cycle) {
        return switch (cycle) {
            case "monthly" -> 1;
            case "yearly" -> MONTHS_PER_YEAR;
            default -> throw new IllegalArgumentException("Unknown billing cycle");
        };
    }

    /** Whole years first, then the remaining months, each step clamping the day to the target month's last day. */
    public static Instant endAfter(Instant start, int months) {
        return start.atZone(ZoneOffset.UTC).plusYears(months / MONTHS_PER_YEAR).plusMonths(months % MONTHS_PER_YEAR)
                .toInstant();
    }

    /** Days after a renewal instant during which a member of this billing mode still holds the plan. */
    public static int graceDays(String mode) {
        return switch (mode) {
            case "manual" -> 0;
            case "auto_debit" -> AUTO_DEBIT_GRACE_DAYS;
            default -> throw new IllegalArgumentException("Unknown billing mode");
        };
    }

    /** The later of two instants; an absent one yields the other. */
    public static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    /**
     * Pins the period a purchase settled at {@code settledAt} bought. It continues the schedule when the
     * settlement falls before the schedule's end plus grace, and starts at the settlement otherwise.
     */
    public static Period pin(Instant settledAt, Instant scheduleEnd, String mode, String cycle) {
        int months = monthsOf(cycle);
        Instant start = settledAt;
        if (scheduleEnd != null && settledAt.isBefore(scheduleEnd.plus(Duration.ofDays(graceDays(mode))))) {
            start = scheduleEnd;
        }
        return new Period(start, endAfter(start, months));
    }
}
