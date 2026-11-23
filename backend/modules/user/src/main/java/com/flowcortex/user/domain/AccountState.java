package com.flowcortex.user.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * The one account-state rule: suspended, else pending confirmation, else locked while the lockout end
 * is after {@code now}, else active. The directory query translates each value to a predicate that
 * partitions the rows the same way.
 */
public enum AccountState {
    ACTIVE("active"),
    PENDING_CONFIRMATION("pending_confirmation"),
    LOCKED("locked"),
    SUSPENDED("suspended");

    private final String wire;

    AccountState(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<AccountState> fromWire(String value) {
        for (AccountState state : values()) {
            if (state.wire.equals(value)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }

    public static AccountState of(UserStatus status, Instant emailConfirmedAt, Instant lockedUntil, Instant now) {
        if (status == UserStatus.SUSPENDED) {
            return SUSPENDED;
        }
        if (emailConfirmedAt == null) {
            return PENDING_CONFIRMATION;
        }
        if (lockedUntil != null && lockedUntil.isAfter(now)) {
            return LOCKED;
        }
        return ACTIVE;
    }
}
