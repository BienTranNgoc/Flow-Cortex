package com.flowcortex.user.pat;

import com.flowcortex.user.application.AccountCredentials;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.RefreshTokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Revokes sessions and personal access tokens together; each revoked token is audited with its reason. */
@Service
class DefaultAccountCredentials implements AccountCredentials {

    static final String REASON = "credentials_reset";

    private final RefreshTokenService refreshTokens;
    private final PersonalAccessTokenRepository tokens;
    private final AuditEventWriter audit;
    private final Clock clock;

    DefaultAccountCredentials(RefreshTokenService refreshTokens, PersonalAccessTokenRepository tokens,
            AuditEventWriter audit, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void revokeAllFor(UUID userId) {
        refreshTokens.revokeAllFor(userId);
        List<PersonalAccessToken> live = tokens.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId);
        if (live.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        tokens.revokeAllByUserId(userId, now);
        for (PersonalAccessToken token : live) {
            audit.write(AuthEventType.PAT_REVOKED, userId, null, null, AuthEventType.metadata("userId",
                    userId.toString(), "tokenId", token.getId().toString(), "name", token.getName(), "reason",
                    REASON));
        }
    }
}
