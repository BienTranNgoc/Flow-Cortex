package com.flowcortex.user.api;

/**
 * What an emailed link opens: a session, or a challenge when the account has TOTP on. {@code
 * passwordSetupRequired}: the account has no password to sign in with. {@code passwordSetupToken} is then the
 * one-time token setting a password needs, and {@code null} otherwise; it is handed out with a challenge too, so
 * the account can set its password once the code has opened the session.
 */
public record ConfirmedEmailSession(SessionOutcome outcome, boolean passwordSetupRequired,
        String passwordSetupToken) {
}
