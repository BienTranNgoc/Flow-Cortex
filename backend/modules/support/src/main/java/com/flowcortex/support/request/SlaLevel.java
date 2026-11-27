package com.flowcortex.support.request;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** How close a request is to its response deadline; only {@code new} and {@code in_progress} requests run a clock. */
enum SlaLevel {
    OK, DUE_SOON, OVERDUE, PAUSED;

    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    boolean atRisk() {
        return this == DUE_SOON || this == OVERDUE;
    }

    /** Strictly greater than a threshold crosses it, so a request exactly at its limit is still due soon. */
    static SlaLevel of(RequestStatus status, Instant lastActivityAt, Instant now, SlaThresholds thresholds) {
        Duration age = Duration.between(lastActivityAt, now);
        return switch (status) {
            case NEW -> level(age, thresholds.newLimit(), thresholds.newWarnCut());
            case IN_PROGRESS -> level(age, thresholds.inProgressLimit(), thresholds.inProgressWarnCut());
            case WAITING_MEMBER, RESOLVED, CLOSED -> PAUSED;
        };
    }

    private static SlaLevel level(Duration age, Duration limit, Duration warnCut) {
        if (age.compareTo(limit) > 0) {
            return OVERDUE;
        }
        return age.compareTo(warnCut) > 0 ? DUE_SOON : OK;
    }
}
