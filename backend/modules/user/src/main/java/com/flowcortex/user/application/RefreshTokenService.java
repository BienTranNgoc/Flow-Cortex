package com.flowcortex.user.application;

import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.domain.AppUser;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateful refresh-token service. Implementations persist hashed rows in
 * {@code refresh_token}, rotate on use, revoke the whole family on reuse.
 */
public interface RefreshTokenService {

    /**
     * Starts a new session (family) for the user and enforces the per-user cap on live sessions.
     * {@code mfaVerifiedAt} is when the sign-in passed a second factor, or {@code null}: it marks the
     * family and every access token it mints.
     */
    AuthSession issueFor(AppUser user, String ip, String userAgent, boolean rememberMe, Instant mfaVerifiedAt);

    /**
     * Whether the token is a live token of this user's remembered session; false for an unknown,
     * used, revoked or expired token and for another account's.
     */
    boolean rememberMeOf(UUID userId, String rawToken);

    /**
     * Exchanges a refresh token for the next one in its family. Every refusal is a returned
     * {@link RotationResult.Rejected}, never an exception, so the family revoke a reuse triggers commits.
     */
    RotationResult rotate(String rawToken, String ip, String userAgent);

    /** Revokes the family the token belongs to and returns the owning user, or empty for an unknown token. */
    Optional<UUID> endSession(String rawToken);

    /** Revokes every live session of the user. */
    void revokeAllFor(UUID userId);
}
