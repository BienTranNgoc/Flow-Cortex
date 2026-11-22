package com.flowcortex.user.application;

import java.util.UUID;

/**
 * The one place an account's credentials are revoked. Every credential type the account can hold
 * (sessions, personal access tokens, unspent step-up proofs) ends here, in the caller's transaction,
 * so it commits or rolls back with the change that triggered it.
 */
public interface AccountCredentials {

    /**
     * Revokes every live session, every unrevoked personal access token and every unspent step-up
     * proof of the user.
     */
    void revokeAllFor(UUID userId);

    /**
     * Revokes the user's personal access tokens only, for an account that stops being an admin: it
     * keeps its sessions, but tokens it can no longer see must not revive when it is promoted again.
     */
    void revokePersonalAccessTokensOnDemotion(UUID userId);
}
