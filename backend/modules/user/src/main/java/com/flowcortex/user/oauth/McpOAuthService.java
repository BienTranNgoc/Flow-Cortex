package com.flowcortex.user.oauth;

import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.oauth.AuthorizationRequestCheck.Checked;
import com.flowcortex.user.oauth.AuthorizationRequestCheck.Params;
import com.flowcortex.user.pat.OAuthTokenIssuer;
import com.flowcortex.user.pat.PatLimitReachedException;
import com.flowcortex.user.pat.PatMfaRequiredException;
import com.flowcortex.user.pat.PatNotEligibleException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent, code exchange and revocation of the MCP authorization server. The access token is a
 * personal access token minted through {@link OAuthTokenIssuer}, so its cap, eligibility and
 * revocation rules are the token feature's own.
 */
@Service
class McpOAuthService {

    private static final Logger LOG = LoggerFactory.getLogger(McpOAuthService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final String INVALID_GRANT = "invalid_grant";

    private static final String TOKEN_NAME_PREFIX = "MCP: ";
    private static final int TOKEN_NAME_MAX = 128;

    private final OAuthClientService clients;
    private final AuthorizationCodeStore codes;
    private final OAuthIssuedTokenRepository issuedTokens;
    private final OAuthTokenIssuer issuer;
    private final StepUpProofs stepUpProofs;
    private final AuditEventWriter audit;
    private final ReplayRevocation replayRevocation;
    private final Clock clock;

    McpOAuthService(OAuthClientService clients, AuthorizationCodeStore codes, OAuthIssuedTokenRepository issuedTokens,
            OAuthTokenIssuer issuer, StepUpProofs stepUpProofs, AuditEventWriter audit,
            ReplayRevocation replayRevocation, Clock clock) {
        this.clients = clients;
        this.codes = codes;
        this.issuedTokens = issuedTokens;
        this.issuer = issuer;
        this.stepUpProofs = stepUpProofs;
        this.audit = audit;
        this.replayRevocation = replayRevocation;
        this.clock = clock;
    }

    /** What the consent page shows; {@code error} is the OAuth error it sends to the verified redirect URI, or null. */
    record CheckView(String clientId, String clientName, String redirectUri, String scope, boolean eligible,
            String error) {
    }

    record ExchangeRequest(String code, String codeVerifier, String redirectUri) {
    }

    /**
     * {@code eligible} is false when a grant from this session would be refused for the account or for the
     * session's missing second factor.
     *
     * @param sessionMfa whether the caller's access token carries the second-factor claim
     * @throws OAuthRequestRefusedException for an unknown client or an unregistered redirect URI
     */
    CheckView check(UUID userId, Params params, boolean sessionMfa) {
        Checked checked = verified(params);
        return new CheckView(checked.client().clientId(), checked.client().clientName(), checked.redirectUri(),
                checked.scope(), issuer.eligibleToManage(userId) && issuer.mfaSatisfied(sessionMfa),
                checked.error());
    }

    /**
     * Spends the step-up proof only after every other refusal has been ruled out, so a refused grant
     * leaves it usable, and stores the code as its hash. The second factor is held to the same rule as
     * creating a token by hand: the code exists only for a session that may mint a token.
     *
     * @param sessionMfa whether the caller's access token carries the second-factor claim
     * @return the redirect URI with {@code code} and, when given, {@code state} added to its query
     * @throws OAuthRequestRefusedException when the request does not check out
     * @throws PatNotEligibleException when the account may not hold tokens
     * @throws PatLimitReachedException at the live-token cap
     * @throws PatMfaRequiredException when the second factor is required and the session did not pass it
     * @throws com.flowcortex.user.api.StepUpRequiredException when the proof is missing, spent or not theirs
     */
    String grant(UUID userId, Params params, String stepUpProof, String ip, String userAgent, boolean sessionMfa) {
        Checked checked = verified(params);
        if (checked.error() != null) {
            throw OAuthRequestRefusedException.withError(checked.error());
        }
        if (!issuer.eligibleToManage(userId)) {
            throw new PatNotEligibleException();
        }
        if (issuer.atCap(userId)) {
            throw new PatLimitReachedException();
        }
        if (!issuer.mfaSatisfied(sessionMfa)) {
            throw new PatMfaRequiredException();
        }
        stepUpProofs.consume(userId, StepUpPurpose.PAT_CREATE, stepUpProof);

        Instant now = clock.instant();
        String code = OpaqueTokens.generateRawToken();
        codes.insert(new AuthorizationCode(OpaqueTokens.hash(code), checked.client().clientId(), userId,
                checked.redirectUri(), params.codeChallenge(), checked.scope(), now, now.plus(CODE_TTL), null, null));
        audit.write(AuthEventType.OAUTH_CONSENT_GRANTED, userId, ip, userAgent,
                AuthEventType.metadata("userId", userId.toString(), "clientId", checked.client().clientId()));
        return redirectTo(checked.redirectUri(), code, params.state());
    }

    /**
     * One transaction that commits on every outcome the caller can see: a refusal is a returned value,
     * never an exception, so the redemption stays spent whatever follows it.
     */
    @Transactional
    ExchangeResult exchange(OAuthClient client, ExchangeRequest request, String ip) {
        Instant now = clock.instant();
        String hash = OpaqueTokens.hash(request.code());
        Optional<AuthorizationCode> redeemed = codes.redeem(hash, now);
        if (redeemed.isEmpty()) {
            Optional<AuthorizationCode> spent = codes.find(hash).filter(AuthorizationCode::redeemed);
            spent.ifPresent(row -> replayRevocation.apply(row, ip));
            return denied(client, spent.isPresent() ? "replayed" : "unknown_or_expired");
        }
        AuthorizationCode code = redeemed.get();
        if (!code.clientId().equals(client.clientId())) {
            return denied(client, "other_client");
        }
        if (!code.redirectUri().equals(request.redirectUri())) {
            return denied(client, "other_redirect_uri");
        }
        if (!Pkce.matches(request.codeVerifier(), code.codeChallenge())) {
            return denied(client, "verifier_mismatch");
        }
        OAuthTokenIssuer.Issued issued;
        try {
            issued = issuer.issue(code.userId(), tokenName(client.clientName()), client.clientId());
        } catch (PatNotEligibleException e) {
            return denied(client, "not_eligible");
        } catch (PatLimitReachedException e) {
            return denied(client, "token_cap");
        }
        codes.markIssued(hash, issued.tokenId());
        issuedTokens.insert(issued.tokenId(), client.clientId(), now);
        long expiresIn = Duration.between(now, issued.expiresAt()).plusMillis(500).getSeconds();
        LOG.info("oauth_exchange outcome=granted client_id={}", client.clientId());
        return new ExchangeResult.Granted(issued.rawToken(), expiresIn, code.scope());
    }

    /** Revokes {@code rawToken} only when this flow issued it to {@code client}; anything else is left alone. */
    @Transactional
    void revoke(OAuthClient client, String rawToken) {
        issuer.findLive(rawToken).filter(live -> issuedTokens.issuedTo(live.tokenId(), client.clientId()))
                .ifPresent(live -> issuer.revoke(live.userId(), live.tokenId(), client.clientId()));
    }

    /** The reason is a fixed word, never a request value, so the log line cannot carry a code or a verifier. */
    private static ExchangeResult denied(OAuthClient client, String reason) {
        LOG.info("oauth_exchange outcome=denied reason={} client_id={}", reason, client.clientId());
        return new ExchangeResult.Denied(INVALID_GRANT);
    }

    static String redirectTo(String redirectUri, String code, String state) {
        StringBuilder url = new StringBuilder(redirectUri).append(redirectUri.contains("?") ? '&' : '?')
                .append("code=").append(encode(code));
        if (state != null && !state.isEmpty()) {
            url.append("&state=").append(encode(state));
        }
        return url.toString();
    }

    private Checked verified(Params params) {
        return switch (AuthorizationRequestCheck.check(params, clients.find(params.clientId()))) {
            case AuthorizationRequestCheck.Refused refused -> throw switch (refused.fault()) {
                case UNKNOWN_CLIENT -> OAuthRequestRefusedException.unknownClient();
                case UNREGISTERED_REDIRECT_URI -> OAuthRequestRefusedException.unregisteredRedirectUri();
            };
            case Checked checked -> checked;
        };
    }

    private static String tokenName(String clientName) {
        String name = TOKEN_NAME_PREFIX + clientName;
        if (name.length() <= TOKEN_NAME_MAX) {
            return name;
        }
        int end = Character.isHighSurrogate(name.charAt(TOKEN_NAME_MAX - 1)) ? TOKEN_NAME_MAX - 1 : TOKEN_NAME_MAX;
        return name.substring(0, end);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
