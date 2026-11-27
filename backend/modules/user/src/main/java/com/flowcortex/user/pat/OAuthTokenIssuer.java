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
     * proof or a session: the grant has already checked the session's second factor, and a code exists
     * only after it did.
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

    /**
     * Whether a session with this second-factor mark may mint a token: always while the console
     * requirement is off, otherwise only for a marked session.
     */
    boolean mfaSatisfied(boolean sessionMfa);
}
