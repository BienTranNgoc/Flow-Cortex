package com.flowcortex.user.oauth;

import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.pat.OAuthTokenIssuer;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** What a replayed code costs: the token its first redemption produced is revoked and the replay is audited. */
@Component
class ReplayRevocation {

    private final OAuthTokenIssuer issuer;
    private final AuditEventWriter audit;

    ReplayRevocation(OAuthTokenIssuer issuer, AuditEventWriter audit) {
        this.issuer = issuer;
        this.audit = audit;
    }

    /** Own transaction, so the revocation stands whatever happens to the exchange that found the replay. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void apply(AuthorizationCode replayed, String ip) {
        Map<String, Object> metadata = AuthEventType.metadata("userId", replayed.userId().toString(), "clientId",
                replayed.clientId());
        if (replayed.issuedTokenId() != null) {
            issuer.revoke(replayed.userId(), replayed.issuedTokenId(), replayed.clientId());
            metadata.put("tokenId", replayed.issuedTokenId().toString());
        }
        audit.write(AuthEventType.OAUTH_CODE_REPLAYED, replayed.userId(), ip, null, metadata);
    }
}
