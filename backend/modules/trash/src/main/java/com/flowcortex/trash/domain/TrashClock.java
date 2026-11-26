package com.flowcortex.trash.domain;

import java.time.Duration;
import java.time.Instant;

/** Expiry arithmetic; {@code expiresAt} is never stored, so a retention change applies to every entry at once. */
public final class TrashClock {

    private static final Duration REMINDER_GRACE = Duration.ofHours(24);

    private TrashClock() {
    }

    public static Instant expiresAt(Instant deletedAt, Duration retention) {
        return deletedAt.plus(retention);
    }

    /** Whole days from {@code now} to {@code expiresAt}, rounded up, never below 0. */
    public static int remainingDays(Instant expiresAt, Instant now) {
        Duration left = Duration.between(now, expiresAt);
        if (left.isNegative() || left.isZero()) {
            return 0;
        }
        long wholeDays = left.toDays();
        return (int) (left.equals(Duration.ofDays(wholeDays)) ? wholeDays : wholeDays + 1);
    }

    /** Entries deleted strictly before this instant have expired. */
    public static Instant sweepCutoff(Instant now, Duration retention) {
        return now.minus(retention);
    }

    /** The latest {@code deleted_at} whose expiry is within {@code lead}; earlier (already expired) entries are due too. */
    public static Instant reminderClaimUpTo(Instant now, Duration retention, Duration lead) {
        return now.minus(retention.minus(lead));
    }

    /** The date a reminder names: the expiry, or {@link #reminderGrace()} after the claim when the expiry has gone by. */
    public static Instant deletionDate(Instant expiresAt, Instant claimedAt) {
        Instant earliestPurge = claimedAt.plus(REMINDER_GRACE);
        return expiresAt.isAfter(earliestPurge) ? expiresAt : earliestPurge;
    }

    /** How long a reminding entry waits after its reminder before the sweep may purge it. */
    public static Duration reminderGrace() {
        return REMINDER_GRACE;
    }

    /** False for an entry never reminded. */
    public static boolean remindedLongEnoughForPurge(Instant remindedAt, Instant now) {
        return remindedAt != null && !remindedAt.isAfter(now.minus(REMINDER_GRACE));
    }
}
