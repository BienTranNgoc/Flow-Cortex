package com.flowcortex.user.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Public API backing {@code /api/v1/auth}.
 */
public interface AuthService {

    /**
     * Creates an active member account owned by the configured registration
     * owner and returns its first {@link AuthSession}.
     * @throws com.flowcortex.shared.web.ConflictException if the email is already taken
     * @throws org.springframework.web.server.ResponseStatusException 403 if no active ADMIN owner is configured
     */
    AuthSession register(String email, String rawPassword, String name, boolean rememberMe, String ip,
            String userAgent);

    /**
     * Verifies the email/password pair and returns an {@link AuthSession}. The password is checked
     * before the account's state is revealed: a wrong password is always the same 401, whatever
     * the account (unknown, suspended or locked).
     * @throws org.springframework.security.authentication.BadCredentialsException if the email is unknown or the
     *         password doesn't match (same exception either way)
     * @throws org.springframework.security.access.AccessDeniedException if the password is right but the account
     *         is {@code SUSPENDED} — no token is minted
     * @throws com.flowcortex.shared.web.AccountLockedException if the password is right but the account is locked
     */
    AuthSession login(String email, String rawPassword, boolean rememberMe, String ip, String userAgent);

    /**
     * Updates the caller's own display name only — email/role/status are admin-mediated, not self-service.
     */
    CurrentUser updateOwnProfile(UUID userId, String name);

    /**
     * Exchanges a Google authorization code (with its PKCE verifier and the nonce the id_token must
     * carry) and signs the Google account in. The account is matched by Google subject only; a new
     * email is registered through the same gate as {@link #register} (owner + rate limit), and an email
     * that already belongs to an account is never linked. The Google call runs outside any transaction.
     * @throws OAuthLoginException when the sign-in is refused (already audited); never an HTTP-mapped error
     */
    AuthSession loginWithGoogle(String code, String codeVerifier, String nonce, boolean rememberMe, String ip,
            String userAgent);

    /** Audits a Google sign-in that was refused before the code exchange (cancelled, invalid state, ...). */
    void recordOAuthFailure(OAuthFailure failure, String ip, String userAgent);

    /**
     * Rotates the given raw refresh token into a new {@link AuthSession} that keeps the original
     * session's expiry. Empty when the token is unknown, expired, revoked or already used (the whole
     * session is then revoked), or when its account is no longer allowed to sign in.
     */
    Optional<AuthSession> refresh(String rawToken, String ip, String userAgent);

    /**
     * Revokes the session the supplied refresh token belongs to; idempotent, and a no-op when the
     * token is absent or unknown. Access tokens already issued stay valid until they expire, at most
     * one access-token lifetime.
     */
    void logout(Optional<String> refreshToken, String ip, String userAgent);
}
