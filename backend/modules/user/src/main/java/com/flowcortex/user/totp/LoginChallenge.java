package com.flowcortex.user.totp;

import java.time.Instant;
import java.util.UUID;

/** A half-finished sign-in: the account passed its first factor and still owes a code. */
public record LoginChallenge(UUID id, UUID userId, boolean rememberMe, ChallengeOrigin origin, Instant expiresAt,
        int failedCodes) {
}
