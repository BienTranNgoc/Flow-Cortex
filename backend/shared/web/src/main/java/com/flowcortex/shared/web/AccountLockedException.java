package com.flowcortex.shared.web;

import java.time.Instant;

/**
 * Thrown when a login attempt is refused because the account is currently locked from too many
 * consecutive failed passwords. Mapped to HTTP 423 (Locked) by {@link GlobalExceptionHandler}.
 */
public class AccountLockedException extends RuntimeException {

    private final LocalizedMessage detail;
    private final Instant lockedUntil;

    public AccountLockedException(LocalizedMessage detail, Instant lockedUntil) {
        super(detail.toString());
        this.detail = detail;
        this.lockedUntil = lockedUntil;
    }

    /** The bundle key for the problem detail. */
    public LocalizedMessage getDetail() {
        return detail;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}
