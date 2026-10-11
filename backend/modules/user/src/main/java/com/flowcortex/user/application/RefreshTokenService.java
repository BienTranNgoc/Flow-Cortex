package com.flowcortex.user.application;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateful refresh-token service. Implementations persist hashed rows in
 * {@code refresh_token}, rotate on use, revoke the whole family on reuse.
 */
public interface RefreshTokenService {

    TokenPair issueFor(AppUser user, String ip, String userAgent, boolean rememberMe);

    TokenPair rotate(String rawToken, String ip, String userAgent);

    void revoke(RefreshToken token, Instant now);

    void revokeFamily(UUID familyId, Instant now);

    Optional<RefreshToken> findByRaw(String rawToken);
}
