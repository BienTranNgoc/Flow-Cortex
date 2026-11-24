package com.flowcortex.user.pat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one door the OAuth authorization server uses on personal access tokens, so the cap and the
 * eligibility rules have a single writer.
 */
public interface OAuthTokenIssuer {

    record Issued(UUID tokenId, String rawToken, Instant expiresAt) {
    }

    record Live(UUID tokenId, UUID userId) {
    }

    /**
     * Mints a token under the lock, eligibility and cap rules of {@code create}, without a step-up
     * proof: the grant checks the session's conditions before it calls this.
     *
     * @throws PatNotEligibleException when the owner is not an active, unlocked, enabled admin
     * @throws PatLimitReachedException at the live-token cap
     */
    Issued issue(UUID userId, String name, String clientId);

    /** The token id and owner of a live token, empty for anything unknown, revoked, expired or not a token. */
    Optional<Live> findLive(String rawToken);

    /** Revokes the user's own live token and audits the client; false when no row changed. */
    boolean revoke(UUID userId, UUID tokenId, String clientId);

    boolean eligibleToManage(UUID userId);

    boolean atCap(UUID userId);
}
