package com.flowcortex.user.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Public API backing {@code /api/v1/auth}.
 */
public interface AuthService {

    /**
     * Creates an active member account owned by the configured registration
     * owner and returns a {@link TokenPairResponse} of (access, refresh) tokens.
     * @throws com.flowcortex.shared.web.ConflictException if the email is already taken
     * @throws org.springframework.web.server.ResponseStatusException 403 if no active ADMIN owner is configured
     */
    TokenPairResponse register(String email, String rawPassword, String name, boolean rememberMe, String ip,
            String userAgent);

    /**
     * Verifies the email/password pair and returns a {@link TokenPairResponse}.
     * @throws BadCredentialsException if the email is unknown or password doesn't match (same exception either way)
     * @throws AccessDeniedException if the account is {@code SUSPENDED} — no token is minted
     */
    TokenPairResponse login(String email, String rawPassword, boolean rememberMe, String ip, String userAgent);

    /**
     * Updates the caller's own display name only — email/role/status are admin-mediated, not self-service.
     */
    CurrentUser updateOwnProfile(UUID userId, String name);

    /**
     * Exchanges a Google OAuth authorization code, finds or creates the user,
     * and returns a {@link TokenPairResponse}.
     */
    TokenPairResponse loginWithGoogle(String code, String state, boolean rememberMe, String ip, String userAgent);

    /**
     * Rotates the given raw refresh token and returns a new {@link TokenPairResponse}.
     */
    TokenPairResponse refresh(String rawToken, String ip, String userAgent);

    /**
     * Revokes the supplied refresh token (if any) and bumps the caller's
     * {@code token_version} so previously-issued access JWTs fail.
     */
    void logout(UUID callerId, Optional<String> refreshToken, String ip, String userAgent);
}
