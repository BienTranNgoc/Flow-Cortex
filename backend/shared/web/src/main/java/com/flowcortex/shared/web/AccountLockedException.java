package com.flowcortex.shared.web;

import java.time.Instant;

/**
 * Thrown when a login attempt is refused because the account is currently locked from too many
 * consecutive failed passwords. Mapped to HTTP 423 (Locked) by {@link GlobalExceptionHandler}.
 */
public class AccountLockedException extends RuntimeException {

    private final Instant lockedUntil;

    public AccountLockedException(String message, Instant lockedUntil) {
        super(message);
        this.lockedUntil = lockedUntil;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
