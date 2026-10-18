package com.flowcortex.user.application;

import com.flowcortex.user.api.AuthSession;
import java.util.UUID;

/** Outcome of {@link RefreshTokenService#rotate}; the ids are null when the token was not recognised. */
public sealed interface RotationResult {

    record Rotated(AuthSession session, UUID userId, UUID familyId) implements RotationResult {
    }

    record Rejected(Reason reason, UUID userId, UUID familyId) implements RotationResult {
    }

    enum Reason {
        UNKNOWN_TOKEN,
        EXPIRED,
        REVOKED,
        /** An already-rotated token came back: the session is revoked as a likely theft. */
        REUSED,
        ACCOUNT_NOT_ALLOWED
    }
}
