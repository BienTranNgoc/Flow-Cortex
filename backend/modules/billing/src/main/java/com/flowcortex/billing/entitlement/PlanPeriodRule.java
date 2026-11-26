package com.flowcortex.billing.entitlement;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The invariant that a sold period outlasts the longest grace window: a period no longer than grace would
 * still be inside grace when it renews, so an unpaid member would never reach the end of it.
 */
final class PlanPeriodRule {

    private static final LocalDate FIRST_START_DAY = LocalDate.of(2024, 1, 1);
    private static final LocalDate LAST_START_DAY = LocalDate.of(2027, 12, 31);

    private PlanPeriodRule() {
    }

    /** A cycle whose shortest period does not outlast grace. */
    record Shortfall(String tierId, long periodDays, long graceDays) {
    }

    /**
     * The fewest days a period of this cycle spans over every start day of the years 2024 to 2027, a 31st on a
     * common February being the floor of a month.
     */
    static long shortestPeriodDays(String cycle) {
        int months = PlanPeriods.monthsOf(cycle);
        long shortest = Long.MAX_VALUE;
        for (LocalDate day = FIRST_START_DAY; !day.isAfter(LAST_START_DAY); day = day.plusDays(1)) {
            Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            long days = Duration.between(start, PlanPeriods.endAfter(start, months)).toDays();
            shortest = Math.min(shortest, days);
        }
        return shortest;
    }

    static Optional<Shortfall> shortfall(String tierId, long periodDays, long graceDays) {
        return periodDays > graceDays ? Optional.empty() : Optional.of(new Shortfall(tierId, periodDays, graceDays));
    }
}
