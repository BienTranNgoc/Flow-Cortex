package com.flowcortex.connector.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.application.ConnectorAccess;
import com.flowcortex.connector.application.ConnectorAccess.Granted;
import com.flowcortex.connector.application.ConnectorFlowSealer;
import com.flowcortex.connector.application.ConnectorFlowSealer.Flow;
import com.flowcortex.connector.application.FlowSealerFixture;
import com.flowcortex.connector.application.google.GoogleConnectorService.AuthorizeStart;
import com.flowcortex.connector.application.google.GoogleConnectorService.GoogleConnection;
import com.flowcortex.connector.application.google.GoogleConnectorService.GoogleHealth;
import com.flowcortex.connector.application.google.GoogleTokens.GoogleAccess;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Fault;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Reply;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.TokenGrant;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GoogleConnectorServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String CODE = "4/secret-code";
    private static final String CLIENT_SECRET = "csecret";
    private static final String ACCESS = "access-secret";
    private static final String REFRESH = "refresh-secret";
    private static final List<String> ALL_SCOPES = GoogleOAuthRules.SCOPES;

    private final ConnectorAccess access = mock(ConnectorAccess.class);
    private final ConnectorCredentials credentials = mock(ConnectorCredentials.class);
    private final GoogleWorkspaceApi api = mock(GoogleWorkspaceApi.class);
    private final GoogleTokens tokens = mock(GoogleTokens.class);
    private final ConnectorFlowSealer sealer = FlowSealerFixture.sealer();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final GoogleProperties on = new GoogleProperties(true, "cid", CLIENT_SECRET,
            "https://app.example/api/v1/connectors/google/callback", null, null, null, null, null, null);
    private final GoogleProperties off = new GoogleProperties(false, null, null, null, null, null, null, null, null,
            null);
    private final UUID member = UUID.randomUUID();
    private final GoogleConnectorService service = service(on);

    private GoogleConnectorService service(GoogleProperties properties) {
        return new GoogleConnectorService(access, credentials, api, tokens, sealer, properties, clock);
    }

    private Flow flow() {
        return Flow.begin(member, GoogleOAuthRules.FLOW_TTL, clock);
    }

    private String callback(Flow flow, String state, String code, String error) {
        return service.callback(flow == null ? null : sealer.seal(flow), state, code, error);
    }

    private static TokenGrant grant(List<String> scopes, String refreshToken) {
        return new TokenGrant(200, ACCESS, refreshToken, 3599, scopes, null, null);
    }

    private static Reply userinfo(String email) {
        return new Reply(200, Map.of("email", email), null, null);
    }

    private static Reply status(int status) {
        return new Reply(status, Map.of(), null, null);
    }

    private static ConnectorException reconnectNotConnected() {
        return GoogleErrorRules.reconnectNotConnected();
    }

    private Granted granted(String refreshToken, Map<String, Object> metadata, List<String> scopes) {
        Instant expiresAt = NOW.plus(Duration.ofMinutes(30));
        ConnectorConnection connection = new ConnectorConnection("google", scopes, metadata, expiresAt, true, true,
                null, NOW, NOW);
        return new Granted(connection, new ConnectorTokens(ACCESS, refreshToken, expiresAt, scopes));
    }

    private GoogleAccess googleAccess(String token) {
        return new GoogleAccess(token, granted(REFRESH, Map.of("email", "stored@example.com"), ALL_SCOPES)
                .connection());
    }

    @Test
    void authorizeChecksMemberAndSwitchThenSealsTheFlowTheUrlCarries() {
        AuthorizeStart start = service.authorize(member);

        verify(access).requireMember(member, true);
        Map<String, String> query = query(start.url());
        assertThat(query).containsEntry("client_id", "cid").containsEntry("code_challenge_method", "S256")
                .containsEntry("access_type", "offline");
        Flow sealed = sealer.open(start.sealedFlow(), NOW).orElseThrow();
        assertThat(sealed.memberId()).isEqualTo(member);
        assertThat(query).containsEntry("state", sealed.state()).containsEntry("code_challenge",
                sealed.codeChallenge());
    }

    @Test
    void authorizeWithTheSwitchOffFailsAndSealsNothing() {
        ConnectorAccess gate = mock(ConnectorAccess.class);
        doThrow(ConnectorException.of(Reason.TURNED_OFF)).when(gate).requireMember(member, false);
        GoogleConnectorService offService = new GoogleConnectorService(gate, credentials, api, tokens, sealer, off,
                clock);

        assertThatThrownBy(() -> offService.authorize(member)).isInstanceOfSatisfying(ConnectorException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.TURNED_OFF));
        verifyNoInteractions(api);
    }

    @Test
    void noCookieIsAnInvalidStateAndNothingIsCalled() {
        assertThat(callback(null, "s1", CODE, null)).isEqualTo("invalid_state");
        verifyNoInteractions(api, credentials);
    }

    @Test
    void aSwappedStateIsAnInvalidState() {
        assertThat(callback(flow(), "s2", CODE, null)).isEqualTo("invalid_state");
        assertThat(callback(flow(), null, CODE, null)).isEqualTo("invalid_state");
        verifyNoInteractions(api, credentials);
    }

    @Test
    void anExpiredFlowIsAnInvalidState() {
        Flow expired = new Flow(member, "s1", "v1", NOW.minusSeconds(1));

        assertThat(callback(expired, "s1", CODE, null)).isEqualTo("invalid_state");
        verifyNoInteractions(api, credentials);
    }

    @Test
    void aCookieThatDoesNotAuthenticateIsAnInvalidState() {
        assertThat(service.callback("k1.AAAA.AAAA.AAAA", "s1", CODE, null)).isEqualTo("invalid_state");
        verifyNoInteractions(api, credentials);
    }

    @Test
    void aDeniedConsentIsCancelledAndAnyOtherGoogleErrorFails() {
        Flow flow = flow();

        assertThat(callback(flow, flow.state(), null, "access_denied")).isEqualTo("cancelled");
        assertThat(callback(flow, flow.state(), null, "server_error")).isEqualTo("failed");
        verifyNoInteractions(api, credentials);
    }

    @Test
    void aMissingOrBlankCodeFails() {
        Flow flow = flow();

        assertThat(callback(flow, flow.state(), null, null)).isEqualTo("failed");
        assertThat(callback(flow, flow.state(), "  ", null)).isEqualTo("failed");
        verifyNoInteractions(api, credentials);
    }

    @Test
    void theSwitchOffIsTurnedOffAndGoogleIsNotCalled() {
        GoogleConnectorService offService = service(off);
        Flow flow = flow();

        assertThat(offService.callback(sealer.seal(flow), flow.state(), CODE, null)).isEqualTo("turned_off");
        verifyNoInteractions(api, credentials);
    }

    @Test
    void aSuspendedMemberFailsWithoutExchangingTheCode() {
        doThrow(ConnectorException.of(Reason.SUSPENDED)).when(access).requireMember(member, true);
        Flow flow = flow();

        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("failed");
        verifyNoInteractions(api, credentials);
    }

    @Test
    void anExchangeGoogleRefusesOrCannotReachFails() {
        Flow flow = flow();
        when(api.exchangeCode(anyString(), anyString())).thenReturn(new TokenGrant(400, null, null, 0, null,
                new Fault("invalid_grant", null, "bad code " + CODE), null));

        String refused = callback(flow, flow.state(), CODE, null);
        assertThat(refused).isEqualTo("failed").doesNotContain(CODE).doesNotContain(flow.verifier());

        doThrow(ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE)).when(api).exchangeCode(anyString(), anyString());
        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("failed");

        doThrow(new IllegalStateException(CODE)).when(api).exchangeCode(anyString(), anyString());
        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("failed");

        verifyNoInteractions(credentials);
    }

    @Test
    void aGrantWithoutTheDriveScopeIsScopeMissingAndNothingIsStored() {
        Flow flow = flow();
        when(api.exchangeCode(CODE, flow.verifier())).thenReturn(grant(
                List.of(GoogleOAuthRules.SCOPE_SHEETS, GoogleOAuthRules.SCOPE_EMAIL), REFRESH));

        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("scope_missing");
        verify(credentials, never()).connect(any(), anyString(), any(), any());
        verify(api, never()).userinfo(anyString());
    }

    @Test
    void aGrantWithoutARefreshTokenFailsAndNothingIsStored() {
        Flow flow = flow();
        when(api.exchangeCode(CODE, flow.verifier())).thenReturn(grant(ALL_SCOPES, null));

        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("failed");
        verify(credentials, never()).connect(any(), anyString(), any(), any());
    }

    @Test
    void aValidCallbackStoresTheGrantOfTheMemberInTheCookie() {
        Flow flow = flow();
        when(api.exchangeCode(CODE, flow.verifier())).thenReturn(grant(ALL_SCOPES, REFRESH));
        when(api.userinfo(ACCESS)).thenReturn(userinfo("m@example.com"));

        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("connected");

        verify(access).requireMember(member, true);
        verify(credentials).connect(eq(member), eq("google"),
                eq(new ConnectorTokens(ACCESS, REFRESH, NOW.plusSeconds(3599), ALL_SCOPES)),
                eq(Map.of("email", "m@example.com")));
    }

    @Test
    void aFailingUserinfoStillStoresTheConnectionWithoutAnEmail() {
        Flow flow = flow();
        when(api.exchangeCode(CODE, flow.verifier())).thenReturn(grant(ALL_SCOPES, REFRESH));
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);

        doThrow(ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE)).when(api).userinfo(ACCESS);
        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("connected");
        doReturn(status(401)).when(api).userinfo(ACCESS);
        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("connected");

        verify(credentials, times(2)).connect(eq(member), eq("google"), any(), metadata.capture());
        assertThat(metadata.getAllValues()).allSatisfy(map -> assertThat(map).containsEntry("email", null));
    }

    @Test
    void aStoreFailureFailsTheCallback() {
        Flow flow = flow();
        when(api.exchangeCode(CODE, flow.verifier())).thenReturn(grant(ALL_SCOPES, REFRESH));
        when(api.userinfo(ACCESS)).thenReturn(userinfo("m@example.com"));
        doThrow(new IllegalStateException("db down")).when(credentials)
                .connect(any(), anyString(), any(), any());

        assertThat(callback(flow, flow.state(), CODE, null)).isEqualTo("failed");
    }

    @Test
    void readShowsTheStoredConnectionWithoutCallingGoogleOrReadingATokenAndNamesMissingScopes() {
        List<String> scopes = List.of(GoogleOAuthRules.SCOPE_SHEETS, GoogleOAuthRules.SCOPE_EMAIL);
        Granted granted = granted(null, Map.of("email", "m@example.com"), scopes);
        when(access.manage(member, "google", true, false)).thenReturn(granted);

        GoogleConnection view = service.read(member);

        assertThat(view).isEqualTo(new GoogleConnection("google", "m@example.com", scopes,
                List.of(GoogleOAuthRules.SCOPE_DRIVE), granted.connection().expiresAt(), true));
        verifyNoInteractions(api, tokens);
    }

    @Test
    void healthIsValidWhenGoogleAcceptsTheToken() {
        when(tokens.forManage(member)).thenReturn(googleAccess("a1"));
        when(api.userinfo("a1")).thenReturn(userinfo("m@example.com"));

        assertThat(service.health(member)).isEqualTo(new GoogleHealth(true, "m@example.com", null));
        verify(tokens, never()).renewNow(any());
    }

    @Test
    void healthRenewsOnceOnA401AndIsValidWhenTheNewTokenIsAccepted() {
        when(tokens.forManage(member)).thenReturn(googleAccess("a1"));
        when(tokens.renewNow(member)).thenReturn(googleAccess("a2"));
        when(api.userinfo("a1")).thenReturn(status(401));
        when(api.userinfo("a2")).thenReturn(userinfo("m@example.com"));

        assertThat(service.health(member)).isEqualTo(new GoogleHealth(true, "m@example.com", null));
    }

    @Test
    void healthIsRejectedWhenTheRenewedTokenIsRefusedToo() {
        when(tokens.forManage(member)).thenReturn(googleAccess("a1"));
        when(tokens.renewNow(member)).thenReturn(googleAccess("a2"));
        when(api.userinfo(anyString())).thenReturn(status(401));

        assertThat(service.health(member)).isEqualTo(new GoogleHealth(false, null, "tokenRejected"));
        verify(credentials, never()).markRevoked(any(), anyString());
    }

    @Test
    void healthIsRejectedNotAnErrorWhenTheRenewalAfterA401IsRefusedAsRevoked() {
        when(tokens.forManage(member)).thenReturn(googleAccess("a1"));
        when(tokens.renewNow(member)).thenThrow(reconnectNotConnected());
        when(api.userinfo("a1")).thenReturn(status(401));

        assertThat(service.health(member)).isEqualTo(new GoogleHealth(false, null, "tokenRejected"));
    }

    @Test
    void healthIsRejectedNotAnErrorWhenTheRenewalBeforeTheCheckIsRefusedAsRevoked() {
        when(tokens.forManage(member)).thenThrow(reconnectNotConnected());

        assertThat(service.health(member)).isEqualTo(new GoogleHealth(false, null, "tokenRejected"));
        verifyNoInteractions(api);
    }

    @Test
    void healthRethrowsANotConnectedThatHasNoProviderReason() {
        when(tokens.forManage(member)).thenThrow(ConnectorException.of(Reason.NOT_CONNECTED));

        assertThatThrownBy(() -> service.health(member)).isInstanceOfSatisfying(ConnectorException.class, ex -> {
            assertThat(ex.reason()).isEqualTo(Reason.NOT_CONNECTED);
            assertThat(ex.providerReason()).isNull();
        });
    }

    @Test
    void healthWithGoogleUnreachableIsUpstreamUnavailable() {
        when(tokens.forManage(member)).thenReturn(googleAccess("a1"));
        when(api.userinfo("a1")).thenThrow(ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE));

        assertThatThrownBy(() -> service.health(member)).isInstanceOfSatisfying(ConnectorException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE));
    }

    @Test
    void healthWithAServerErrorFromGoogleIsUpstreamUnavailable() {
        when(tokens.forManage(member)).thenReturn(googleAccess("a1"));
        when(api.userinfo("a1")).thenReturn(status(503));

        assertThatThrownBy(() -> service.health(member)).isInstanceOfSatisfying(ConnectorException.class,
                ex -> assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE));
    }

    @Test
    void disconnectRevokesTheRefreshTokenAtGoogle() {
        when(access.manage(member, "google", true, true)).thenReturn(granted(REFRESH, Map.of(), ALL_SCOPES));

        service.revokeBeforeDisconnect(member);

        verify(api).revoke(REFRESH);
    }

    @Test
    void disconnectRevokesTheAccessTokenWhenThereIsNoRefreshToken() {
        when(access.manage(member, "google", true, true)).thenReturn(granted(null, Map.of(), ALL_SCOPES));

        service.revokeBeforeDisconnect(member);

        verify(api).revoke(ACCESS);
    }

    @Test
    void aRevokeFailureDoesNotStopTheDisconnect() {
        when(access.manage(member, "google", true, true)).thenReturn(granted(REFRESH, Map.of(), ALL_SCOPES));
        doThrow(ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE)).when(api).revoke(anyString());

        service.revokeBeforeDisconnect(member);

        verify(api).revoke(REFRESH);
    }

    @Test
    void everyConnectorRefusalBeforeTheRevokeIsSwallowedAndGoogleIsNotCalled() {
        for (Reason reason : List.of(Reason.SUSPENDED, Reason.NOT_CONNECTED, Reason.CREDENTIAL_UNREADABLE)) {
            doThrow(ConnectorException.of(reason)).when(access).manage(member, "google", true, true);

            service.revokeBeforeDisconnect(member);
        }

        verifyNoInteractions(api);
    }

    @Test
    void disconnectWithTheSwitchOffDoesNotCallGoogleOrReadTheCredential() {
        service(off).revokeBeforeDisconnect(member);

        verifyNoInteractions(api, access);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> query = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            query.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return query;
    }
}
