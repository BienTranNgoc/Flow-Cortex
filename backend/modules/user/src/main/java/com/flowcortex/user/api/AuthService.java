package com.flowcortex.user.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Public API backing {@code /api/v1/auth}.
 */
public interface AuthService {

    /**
     * Creates an active member account owned by the configured registration owner; its display name
     * is "first last" and {@code username} may be {@code null}. With email confirmation on, the account
     * is created unconfirmed, a confirmation link is mailed after commit and the result is empty;
     * otherwise the account is confirmed and its first {@link AuthSession} is returned. An email whose
     * account is still unconfirmed is registered again: that account takes the new password, names and
     * username, and only a newly mailed link confirms it.
     * @throws com.flowcortex.shared.web.ConflictException if the email belongs to a confirmed account, or
     *         the username is already taken
     * @throws org.springframework.web.server.ResponseStatusException 403 if no active ADMIN owner is configured
     * @throws org.springframework.security.access.AccessDeniedException if confirmation is off and the
     *         unconfirmed account registered again is suspended — no session is issued
     */
    Optional<AuthSession> register(String email, String rawPassword, String firstName, String lastName,
            String username, boolean rememberMe, String ip, String userAgent);

    /**
     * Spends an emailed confirmation link, confirms its account and signs it in. The first confirmation
     * drops the password, username and names chosen at registration and ends every earlier session,
     * since nobody had proven they own the address when they were chosen. An account without a password
     * gets a password-setup token for {@link #setPassword}.
     * @throws com.flowcortex.shared.web.UnprocessableEntityException code {@code CONFIRMATION_LINK_INVALID}
     *         when the link is unknown, used, superseded or expired
     * @throws org.springframework.security.access.AccessDeniedException if the account is suspended
     */
    ConfirmedEmailSession confirmEmail(String rawToken, String ip, String userAgent);

    /**
     * Spends an emailed set-password link and signs its account in with a password-setup token.
     * @throws com.flowcortex.shared.web.UnprocessableEntityException code {@code PASSWORD_LINK_INVALID},
     *         the same for a link that is unknown, used, superseded, expired or whose account has a
     *         password by now
     * @throws org.springframework.security.access.AccessDeniedException if the account is suspended
     */
    ConfirmedEmailSession openPasswordLink(String rawToken, String ip, String userAgent);

    /**
     * Gives a signed-in account without a password its first one, with the names and username chosen
     * alongside it, spending the setup token an emailed link handed out. Every session of the account
     * ends, a new one replaces the caller's, and the account's address is told. Changing an existing
     * password is not offered.
     * @throws com.flowcortex.shared.web.UnprocessableEntityException code {@code PASSWORD_SETUP_TOKEN_INVALID}
     *         when the setup token is unknown, used, expired or another account's
     * @throws com.flowcortex.shared.web.ConflictException if the account already has a password, or the
     *         username is already taken
     * @throws org.springframework.security.access.AccessDeniedException if the account is suspended
     */
    AuthSession setPassword(UUID userId, PasswordSetup setup, String ip, String userAgent);

    /**
     * Mails a new link and retires the earlier ones when the email belongs to an active account that
     * needs one: a confirmation link while it is unconfirmed, a set-password link once it is confirmed
     * without a password. Answers the same whether or not it does. Shares the registration limits.
     */
    void resendConfirmation(String email, String ip, String userAgent);

    /**
     * Verifies the email/password pair and returns an {@link AuthSession}. The password is checked
     * before the account's state is revealed: a wrong password is always the same 401, whatever
     * the account (unknown, suspended or locked).
     * @throws org.springframework.security.authentication.BadCredentialsException if the email is unknown or the
     *         password doesn't match (same exception either way)
     * @throws org.springframework.security.access.AccessDeniedException if the password is right but the account
     *         is {@code SUSPENDED} — no token is minted
     * @throws com.flowcortex.shared.web.AccountLockedException if the password is right but the account is locked
     * @throws com.flowcortex.user.emailconfirm.EmailNotConfirmedException (403 {@code EMAIL_NOT_CONFIRMED})
     *         if the password is right but the account has not confirmed its email
     */
    AuthSession login(String email, String rawPassword, boolean rememberMe, String ip, String userAgent);

    /**
     * Updates the caller's own display name only — email/role/status are admin-mediated, not self-service.
     */
    CurrentUser updateOwnProfile(UUID userId, String name);

    /**
     * Exchanges a Google authorization code (with its PKCE verifier and the nonce the id_token must
     * carry) and signs the Google account in. The account is matched by Google subject; a new email is
     * registered through the same gate as {@link #register} (owner + rate limit). An email whose account
     * is still unconfirmed is taken over: Google has verified it, so the account is linked, confirmed,
     * loses its password and username, and takes the Google name. An email that belongs to a confirmed
     * account is never linked. The Google call runs outside any transaction.
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
