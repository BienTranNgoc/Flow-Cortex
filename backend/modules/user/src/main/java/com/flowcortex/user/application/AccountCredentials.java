package com.flowcortex.user.application;

import java.util.UUID;

/**
 * The one place an account's credentials are revoked. Every credential type the account can hold
 * (sessions, personal access tokens) ends here, in the caller's transaction, so it commits or rolls
 * back with the change that triggered it.
 */
public interface AccountCredentials {

    /** Revokes every live session and every unrevoked personal access token of the user. */
    void revokeAllFor(UUID userId);
}
