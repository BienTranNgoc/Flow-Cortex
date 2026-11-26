package com.flowcortex.user.accountdeletion;

import java.time.Instant;

/** Where an account stands on deletion; derived from the deadline on every read, never stored. */
public enum AccountDeletionState {
    NONE,
    PENDING,
    EXPIRED;

    /** {@code NONE} without a request, {@code EXPIRED} at or past the deadline or when the deadline is missing. */
    public static AccountDeletionState of(Instant requestedAt, Instant scheduledAt, Instant now) {
        if (requestedAt == null) {
            return NONE;
        }
        if (scheduledAt == null || !now.isBefore(scheduledAt)) {
            return EXPIRED;
        }
        return PENDING;
    }
}
