package com.flowcortex.user.api;

import java.util.UUID;

/**
 * Public API backing {@code /api/v1/auth}.
 */
public interface AuthService {

    /**
     * Verifies the email/password pair and mints a signed JWT (subject = the user's id).
     * @throws BadCredentialsException if the email is unknown or password doesn't match (same exception either way)
     * @throws AccessDeniedException if the account is {@code SUSPENDED} — no token is minted
     */
    String login(String email, String rawPassword);

    /**
     * Updates the caller's own display name only — email/role/status are admin-mediated, not self-service.
     * @throws NotFoundException if {@code userId} no longer resolves to a row (a token can outlive the row)
     */
    CurrentUser updateOwnProfile(UUID userId, String name);
}
