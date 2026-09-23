package com.flowcortex.user.api;

import java.util.UUID;

/**
 * Public API backing {@code /api/v1/auth}.
 */
public interface AuthService {

    /**
     * Verifies the email/password pair and mints a signed JWT (subject = the
     * user's id).
     *
     * @throws org.springframework.security.authentication.BadCredentialsException
     *         if the email is unknown or the password does not match — the
     *         same exception either way, so the response never discloses
     *         which one was wrong
     * @throws org.springframework.security.access.AccessDeniedException
     *         if the credentials are correct but the account is {@code
     *         SUSPENDED} — no token is minted
     */
    String login(String email, String rawPassword);

    /**
     * Updates the caller's own display name only — email/role/status are not
     * self-service (email correction is admin-mediated).
     *
     * @throws com.flowcortex.shared.web.NotFoundException if {@code userId}
     *         no longer resolves to a row (defensive: a token can outlive the
     *         row it was minted for)
     */
    CurrentUser updateOwnProfile(UUID userId, String name);
}
