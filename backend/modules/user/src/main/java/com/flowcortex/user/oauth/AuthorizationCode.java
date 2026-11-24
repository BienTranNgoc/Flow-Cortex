package com.flowcortex.user.oauth;

import java.time.Instant;
import java.util.UUID;

/** One issued authorization code as stored: the code itself is never kept, only its hash. */
record AuthorizationCode(String codeHash, String clientId, UUID userId, String redirectUri, String codeChallenge,
        String scope, Instant createdAt, Instant expiresAt, Instant redeemedAt, UUID issuedTokenId) {

    boolean redeemed() {
        return redeemedAt != null;
    }
}
