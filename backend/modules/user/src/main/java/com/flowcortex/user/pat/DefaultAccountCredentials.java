package com.flowcortex.user.pat;

import com.flowcortex.user.application.AccountCredentials;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.RefreshTokenService;
import com.flowcortex.user.stepup.StepUpProofRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes sessions, personal access tokens and unspent step-up proofs together; each revoked token
 * is audited with its reason.
 */
@Service
class DefaultAccountCredentials implements AccountCredentials {

    static final String REASON = "credentials_reset";
    static final String REASON_DEMOTED = "role_demoted";

    private final RefreshTokenService refreshTokens;
    private final PersonalAccessTokenRepository tokens;
    private final StepUpProofRepository stepUpProofs;
    private final AuditEventWriter audit;
    private final Clock clock;

    DefaultAccountCredentials(RefreshTokenService refreshTokens, PersonalAccessTokenRepository tokens,
            StepUpProofRepository stepUpProofs, AuditEventWriter audit, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.stepUpProofs = stepUpProofs;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void revokeAllFor(UUID userId) {
        refreshTokens.revokeAllFor(userId);
        stepUpProofs.deleteUnspentByUserId(userId);
        revokeTokens(userId, REASON);
    }

    @Override
    @Transactional
    public void revokePersonalAccessTokensOnDemotion(UUID userId) {
        revokeTokens(userId, REASON_DEMOTED);
    }

    private void revokeTokens(UUID userId, String reason) {
        List<PersonalAccessToken> live = tokens.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId);
        if (live.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        tokens.revokeAllByUserId(userId, now);
        for (PersonalAccessToken token : live) {
            audit.write(AuthEventType.PAT_REVOKED, userId, null, null, AuthEventType.metadata("userId",
                    userId.toString(), "tokenId", token.getId().toString(), "name", token.getName(), "reason",
                    reason));
        }
    }
}
