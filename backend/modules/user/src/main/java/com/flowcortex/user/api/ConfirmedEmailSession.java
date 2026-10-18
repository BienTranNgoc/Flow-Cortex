package com.flowcortex.user.api;

/**
 * The session an emailed link opens. {@code passwordSetupRequired}: the account has no password to
 * sign in with. {@code passwordSetupToken} is then the one-time token setting a password needs, and
 * {@code null} otherwise.
 */
public record ConfirmedEmailSession(AuthSession session, boolean passwordSetupRequired, String passwordSetupToken) {
}
