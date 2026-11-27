package com.flowcortex.user.totp;

import java.time.Instant;
import java.util.UUID;

/**
 * One account's TOTP enrolment. Kept off {@code AppUser} and package-private so the encrypted secret cannot
 * reach a response that maps the user. {@code enabledAt == null} means the setup is still pending.
 */
record UserTotp(UUID userId, String secretEncrypted, Instant enabledAt, Long lastUsedStep, int failedAttempts,
        Instant lockedUntil) {

    boolean isEnabled() {
        return enabledAt != null;
    }

    boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
