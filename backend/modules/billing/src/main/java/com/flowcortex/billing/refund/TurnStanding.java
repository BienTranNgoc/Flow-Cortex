package com.flowcortex.billing.refund;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Where a member stands against the change-of-mind turn limit.
 *
 * @param enabled whether the limit applies at all
 * @param used how many counted turns lie inside the window now
 * @param limit how many turns a member may hold at once
 * @param windowMonths the length of the window in calendar months
 * @param nextTurnAt when a turn next opens; null while one is free and while the limit is off
 */
record TurnStanding(boolean enabled, int used, int limit, int windowMonths, Instant nextTurnAt) {

    static final Set<String> COUNTED_STATUSES = Set.of("pending", "approved", "auto_approved");

    /** A request as far as the turn count is concerned. */
    record CountableRequest(String status, Instant createdAt) {
    }

    boolean free() {
        return !enabled || used < limit;
    }

    /**
     * The creation instants of the requests that hold a turn: in {@code pending}, {@code approved} or
     * {@code auto_approved} and created strictly after {@code now} minus the window (a turn exactly on the
     * boundary has already slid out). Months are UTC calendar months, the day clamped to the target month.
     */
    static List<Instant> counted(List<CountableRequest> requests, Instant now, int windowMonths) {
        Instant windowStart = windowStart(now, windowMonths);
        return requests.stream()
                .filter(request -> COUNTED_STATUSES.contains(request.status()))
                .map(CountableRequest::createdAt)
                .filter(createdAt -> createdAt.isAfter(windowStart))
                .toList();
    }

    /**
     * The standing from already window-filtered turn instants. With no turn free the next one opens when
     * enough turns have slid out for the count to fall below the limit: the counted instant at ascending
     * index {@code used - limit} plus the window, which is the oldest one only while the count equals the limit.
     */
    static TurnStanding of(List<Instant> turnsAt, Instant now, int limit, int windowMonths, boolean enabled) {
        int used = turnsAt.size();
        if (!enabled || used < limit) {
            return new TurnStanding(enabled, used, limit, windowMonths, null);
        }
        Instant opening = turnsAt.stream().sorted(Comparator.naturalOrder()).toList().get(used - limit);
        return new TurnStanding(enabled, used, limit, windowMonths, shiftMonths(opening, windowMonths));
    }

    /** The instant a request must be created strictly after to hold a turn. */
    static Instant windowStart(Instant now, int windowMonths) {
        return shiftMonths(now, -windowMonths);
    }

    private static Instant shiftMonths(Instant from, int months) {
        return from.atOffset(ZoneOffset.UTC).plusMonths(months).toInstant();
    }
}
