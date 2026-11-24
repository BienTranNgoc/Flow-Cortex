package com.flowcortex.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.oauth.AuthorizationRequestCheck.Params;
import com.flowcortex.user.oauth.McpOAuthService.CheckView;
import com.flowcortex.user.oauth.McpOAuthService.ExchangeRequest;
import com.flowcortex.user.pat.OAuthTokenIssuer;
import com.flowcortex.user.pat.OAuthTokenIssuer.Live;
import com.flowcortex.user.pat.PatLimitReachedException;
import com.flowcortex.user.pat.PatNotEligibleException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class OAuthTokenExchangeTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CALLBACK = "https://claude.ai/api/mcp/auth_callback";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String CODE = "c0de-the-raw-code-value-0123456789abcdefghij";
    private static final String SECRET = "s3cr3t-client-secret";
    private static final String IP = "203.0.113.7";

    private final OAuthClientService clients = mock(OAuthClientService.class);
    private final AuthorizationCodeStore codes = mock(AuthorizationCodeStore.class);
    private final OAuthIssuedTokenRepository issuedTokens = mock(OAuthIssuedTokenRepository.class);
    private final OAuthTokenIssuer issuer = mock(OAuthTokenIssuer.class);
    private final StepUpProofs stepUp = mock(StepUpProofs.class);
    private final AuditEventWriter audit = mock(AuditEventWriter.class);
    private final McpOAuthService service = new McpOAuthService(clients, codes, issuedTokens, issuer, stepUp, audit,
            new ReplayRevocation(issuer, audit), CLOCK);

    private final UUID userId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();
    private final OAuthClient client = new OAuthClient("mcp_ab12", OpaqueTokens.hash(SECRET), "Claude",
            List.of(CALLBACK), "client_secret_post", NOW.minusSeconds(3600));

    private AuthorizationCode storedCode(Instant redeemedAt, UUID issuedTokenId) {
        return new AuthorizationCode(OpaqueTokens.hash(CODE), "mcp_ab12", userId, CALLBACK, CHALLENGE, "mcp",
                NOW.minusSeconds(60), NOW.plusSeconds(240), redeemedAt, issuedTokenId);
    }

    private ExchangeRequest request(String verifier, String redirectUri) {
        return new ExchangeRequest(CODE, verifier, redirectUri);
    }

    private void redemptionWins() {
        when(codes.redeem(OpaqueTokens.hash(CODE), NOW)).thenReturn(Optional.of(storedCode(NOW, null)));
    }

    private void issuerMints() {
        when(issuer.issue(eq(userId), anyString(), eq("mcp_ab12")))
                .thenReturn(new OAuthTokenIssuer.Issued(tokenId, "fc_pat_raw", NOW.plus(Duration.ofDays(90))));
    }

    @Test
    void aValidCodeMintsATokenNamedAfterTheClientAndLinksIt() {
        redemptionWins();
        issuerMints();

        ExchangeResult result = service.exchange(client, request(VERIFIER, CALLBACK), IP);

        assertThat(result).isEqualTo(new ExchangeResult.Granted("fc_pat_raw", 7_776_000L, "mcp"));
        verify(issuer).issue(userId, "MCP: Claude", "mcp_ab12");
        verify(codes).markIssued(OpaqueTokens.hash(CODE), tokenId);
        verify(issuedTokens).insert(tokenId, "mcp_ab12", NOW);
    }

    @Test
    void theTokenNameIsCutTo128Characters() {
        OAuthClient longName = new OAuthClient("mcp_ab12", null, "n".repeat(130), List.of(CALLBACK), "none", NOW);
        redemptionWins();
        issuerMints();

        service.exchange(longName, request(VERIFIER, CALLBACK), IP);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(issuer).issue(eq(userId), name.capture(), eq("mcp_ab12"));
        assertThat(name.getValue()).hasSize(128).startsWith("MCP: n");
    }

    @Test
    void aRedeemedCodeRevokesTheTokenItProducedAndAuditsTheReplay() {
        when(codes.redeem(OpaqueTokens.hash(CODE), NOW)).thenReturn(Optional.empty());
        when(codes.find(OpaqueTokens.hash(CODE))).thenReturn(Optional.of(storedCode(NOW.minusSeconds(30), tokenId)));

        ExchangeResult result = service.exchange(client, request(VERIFIER, CALLBACK), IP);

        assertThat(result).isEqualTo(new ExchangeResult.Denied("invalid_grant"));
        verify(issuer).revoke(userId, tokenId, "mcp_ab12");
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).write(eq(AuthEventType.OAUTH_CODE_REPLAYED), eq(userId), eq(IP), isNull(), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("clientId", "mcp_ab12").containsEntry("tokenId",
                tokenId.toString());
        verify(issuer, never()).issue(any(), any(), any());
    }

    @Test
    void aCodeBurnedByAWrongVerifierIsAReplayWithNoTokenToRevoke() {
        when(codes.redeem(OpaqueTokens.hash(CODE), NOW)).thenReturn(Optional.empty());
        when(codes.find(OpaqueTokens.hash(CODE))).thenReturn(Optional.of(storedCode(NOW.minusSeconds(30), null)));

        ExchangeResult result = service.exchange(client, request(VERIFIER, CALLBACK), IP);

        assertThat(result).isEqualTo(new ExchangeResult.Denied("invalid_grant"));
        verify(issuer, never()).revoke(any(), any(), any());
        verify(audit).write(eq(AuthEventType.OAUTH_CODE_REPLAYED), eq(userId), eq(IP), isNull(), any());
    }

    @Test
    void anUnknownOrExpiredCodeIsRefusedWithoutAReplayEvent() {
        when(codes.redeem(any(), any())).thenReturn(Optional.empty());
        when(codes.find(any())).thenReturn(Optional.empty());
        assertThat(service.exchange(client, request(VERIFIER, CALLBACK), IP))
                .isEqualTo(new ExchangeResult.Denied("invalid_grant"));

        when(codes.find(any())).thenReturn(Optional.of(storedCode(null, null)));
        assertThat(service.exchange(client, request(VERIFIER, CALLBACK), IP))
                .isEqualTo(new ExchangeResult.Denied("invalid_grant"));

        verifyNoInteractions(audit);
        verify(issuer, never()).issue(any(), any(), any());
    }

    @Test
    void anotherClientAnotherRedirectUriOrAWrongVerifierIsRefusedAndTheRedemptionKept() {
        OAuthClient other = new OAuthClient("mcp_ef56", null, "Other", List.of(CALLBACK), "none", NOW);
        redemptionWins();

        assertThat(service.exchange(other, request(VERIFIER, CALLBACK), IP))
                .isEqualTo(new ExchangeResult.Denied("invalid_grant"));
        assertThat(service.exchange(client, request(VERIFIER, CALLBACK + "/"), IP))
                .isEqualTo(new ExchangeResult.Denied("invalid_grant"));
        assertThat(service.exchange(client, request("wrong-verifier-wrong-verifier-wrong-verifier-1", CALLBACK), IP))
                .isEqualTo(new ExchangeResult.Denied("invalid_grant"));
        assertThat(service.exchange(client, request(null, CALLBACK), IP))
                .isEqualTo(new ExchangeResult.Denied("invalid_grant"));

        verify(issuer, never()).issue(any(), any(), any());
        verify(codes, never()).markIssued(any(), any());
    }

    @Test
    void anAccountThatLostEligibilityOrHitTheCapSinceConsentIsRefusedWithInvalidGrant() {
        redemptionWins();
        when(issuer.issue(eq(userId), anyString(), eq("mcp_ab12"))).thenThrow(new PatNotEligibleException());
        assertThat(service.exchange(client, request(VERIFIER, CALLBACK), IP))
                .isEqualTo(new ExchangeResult.Denied("invalid_grant"));

        when(issuer.issue(eq(userId), anyString(), eq("mcp_ab12"))).thenThrow(new PatLimitReachedException());
        assertThat(service.exchange(client, request(VERIFIER, CALLBACK), IP))
                .isEqualTo(new ExchangeResult.Denied("invalid_grant"));

        verify(codes, never()).markIssued(any(), any());
        verify(issuedTokens, never()).insert(any(), any(), any());
    }

    @Test
    void noAuditMetadataHoldsTheCodeTheVerifierOrTheSecret() {
        when(codes.redeem(any(), any())).thenReturn(Optional.empty());
        when(codes.find(any())).thenReturn(Optional.of(storedCode(NOW.minusSeconds(5), tokenId)));

        service.exchange(client, request(VERIFIER, CALLBACK), IP);

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).write(any(), any(), any(), any(), metadata.capture());
        assertThat(metadata.getValue().toString()).doesNotContain(CODE).doesNotContain(VERIFIER)
                .doesNotContain(SECRET).doesNotContain(OpaqueTokens.hash(CODE));
    }

    private Params params(String redirectUri, String challenge) {
        return new Params("code", "mcp_ab12", redirectUri, challenge, "S256", "mcp", "xyz", null);
    }

    private void clientIsRegistered() {
        when(clients.find("mcp_ab12")).thenReturn(Optional.of(client));
    }

    @Test
    void aGrantChecksEligibilityThenTheCapThenSpendsTheProofThenStoresTheCodeAsItsHash() {
        clientIsRegistered();
        when(issuer.eligibleToManage(userId)).thenReturn(true);
        when(issuer.atCap(userId)).thenReturn(false);

        String redirectTo = service.grant(userId, params(CALLBACK, CHALLENGE), "proof", IP, "agent");

        InOrder order = inOrder(issuer, stepUp, codes, audit);
        order.verify(issuer).eligibleToManage(userId);
        order.verify(issuer).atCap(userId);
        order.verify(stepUp).consume(userId, StepUpPurpose.PAT_CREATE, "proof");
        ArgumentCaptor<AuthorizationCode> stored = ArgumentCaptor.forClass(AuthorizationCode.class);
        order.verify(codes).insert(stored.capture());
        order.verify(audit).write(eq(AuthEventType.OAUTH_CONSENT_GRANTED), eq(userId), eq(IP), eq("agent"), any());

        assertThat(redirectTo).startsWith(CALLBACK + "?code=").endsWith("&state=xyz");
        String code = redirectTo.substring((CALLBACK + "?code=").length(), redirectTo.indexOf("&state="));
        assertThat(code).hasSize(43);
        AuthorizationCode row = stored.getValue();
        assertThat(row.codeHash()).isEqualTo(OpaqueTokens.hash(code)).isNotEqualTo(code);
        assertThat(row.clientId()).isEqualTo("mcp_ab12");
        assertThat(row.userId()).isEqualTo(userId);
        assertThat(row.redirectUri()).isEqualTo(CALLBACK);
        assertThat(row.codeChallenge()).isEqualTo(CHALLENGE);
        assertThat(row.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(row.redeemedAt()).isNull();
    }

    @Test
    void aGrantForAnIneligibleAccountLeavesTheProofUntouched() {
        clientIsRegistered();
        when(issuer.eligibleToManage(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.grant(userId, params(CALLBACK, CHALLENGE), "proof", IP, "agent"))
                .isInstanceOf(PatNotEligibleException.class);

        verifyNoInteractions(stepUp);
        verify(codes, never()).insert(any());
    }

    @Test
    void aGrantAtTheCapLeavesTheProofUntouched() {
        clientIsRegistered();
        when(issuer.eligibleToManage(userId)).thenReturn(true);
        when(issuer.atCap(userId)).thenReturn(true);

        assertThatThrownBy(() -> service.grant(userId, params(CALLBACK, CHALLENGE), "proof", IP, "agent"))
                .isInstanceOf(PatLimitReachedException.class);

        verifyNoInteractions(stepUp);
        verify(codes, never()).insert(any());
    }

    @Test
    void aGrantWhoseRequestDoesNotCheckOutSpendsNothingAndStoresNothing() {
        clientIsRegistered();

        assertThatThrownBy(() -> service.grant(userId, params("https://evil.example/cb", CHALLENGE), "proof", IP, null))
                .isInstanceOf(OAuthRequestRefusedException.class);
        assertThatThrownBy(() -> service.grant(userId, params(CALLBACK, "short"), "proof", IP, null))
                .isInstanceOf(OAuthRequestRefusedException.class).hasMessageContaining("invalid_request");

        verifyNoInteractions(stepUp);
        verify(codes, never()).insert(any());
        verify(issuer, never()).eligibleToManage(any());
    }

    @Test
    void aMissingProofSurfacesTheStepUpRefusalAndStoresNoCode() {
        clientIsRegistered();
        when(issuer.eligibleToManage(userId)).thenReturn(true);
        org.mockito.Mockito.doThrow(new com.flowcortex.user.api.StepUpRequiredException())
                .when(stepUp).consume(userId, StepUpPurpose.PAT_CREATE, null);

        assertThatThrownBy(() -> service.grant(userId, params(CALLBACK, CHALLENGE), null, IP, null))
                .isInstanceOf(com.flowcortex.user.api.StepUpRequiredException.class);

        verify(codes, never()).insert(any());
        verifyNoInteractions(audit);
    }

    @Test
    void redirectToAppendsCodeAndStateToAnExistingQueryAndEncodesState() {
        assertThat(McpOAuthService.redirectTo("https://a.example/cb?tenant=7", "c0de", null))
                .isEqualTo("https://a.example/cb?tenant=7&code=c0de");
        assertThat(McpOAuthService.redirectTo("https://a.example/cb", "c0de", "a b&c=d"))
                .isEqualTo("https://a.example/cb?code=c0de&state=a%20b%26c%3Dd");
        assertThat(McpOAuthService.redirectTo("https://a.example/cb", "c0de", "")).doesNotContain("state");
    }

    @Test
    void checkReportsEligibilityAndTheRedirectableError() {
        clientIsRegistered();
        when(issuer.eligibleToManage(userId)).thenReturn(false);

        CheckView view = service.check(userId, params(CALLBACK, null));

        assertThat(view).isEqualTo(new CheckView("mcp_ab12", "Claude", CALLBACK, "mcp", false, "invalid_request"));
    }

    @Test
    void checkRefusesAnUnknownClientAndAnUnregisteredRedirectUri() {
        when(clients.find("mcp_ab12")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.check(userId, params(CALLBACK, CHALLENGE)))
                .isInstanceOf(OAuthRequestRefusedException.class).hasMessageContaining("oauth.invalidClient");

        clientIsRegistered();
        assertThatThrownBy(() -> service.check(userId, params("https://evil.example/cb", CHALLENGE)))
                .isInstanceOf(OAuthRequestRefusedException.class).hasMessageContaining("oauth.invalidRedirectUri");
    }

    @Test
    void aClientRevokesOnlyATokenThisFlowIssuedToIt() {
        when(issuer.findLive("fc_pat_own")).thenReturn(Optional.of(new Live(tokenId, userId)));
        when(issuedTokens.issuedTo(tokenId, "mcp_ab12")).thenReturn(true);
        when(issuer.findLive("fc_pat_profile")).thenReturn(Optional.of(new Live(UUID.randomUUID(), userId)));
        when(issuer.findLive("fc_pat_unknown")).thenReturn(Optional.empty());

        service.revoke(client, "fc_pat_own");
        service.revoke(client, "fc_pat_profile");
        service.revoke(client, "fc_pat_unknown");

        verify(issuer).revoke(userId, tokenId, "mcp_ab12");
        verify(issuer, org.mockito.Mockito.times(1)).revoke(any(), any(), any());
    }
}
