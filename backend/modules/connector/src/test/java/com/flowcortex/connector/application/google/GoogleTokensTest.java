package com.flowcortex.connector.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.flowcortex.connector.application.google.GoogleTokens.GoogleAccess;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Fault;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.RefreshResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoogleTokensTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String OLD_ACCESS = "old-access-secret";
    private static final String REFRESH = "refresh-secret";

    private final ConnectorAccess access = mock(ConnectorAccess.class);
    private final ConnectorCredentials credentials = mock(ConnectorCredentials.class);
    private final GoogleWorkspaceApi api = mock(GoogleWorkspaceApi.class);
    private final GoogleProperties properties = new GoogleProperties(true, "cid", "csecret",
            "https://app.example/api/v1/connectors/google/callback", null, null, null, null, null, null);
    private final GoogleTokens tokens = new GoogleTokens(access, credentials, api, properties,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID member = UUID.randomUUID();

    private Granted granted(Duration expiresIn) {
        Instant expiresAt = NOW.plus(expiresIn);
        ConnectorConnection connection = new ConnectorConnection("google", List.of(), Map.of(), expiresAt, true, true,
                null, NOW, NOW);
        return new Granted(connection, new ConnectorTokens(OLD_ACCESS, REFRESH, expiresAt, List.of()));
    }

    private static RefreshResult refreshed(String refreshToken) {
        return new RefreshResult(200, "new-access-secret", refreshToken, 3599, null, null, null);
    }

    private static RefreshResult refused(int status, String reason) {
        return new RefreshResult(status, null, null, 0, null, new Fault(reason, null, "Google says " + REFRESH), null);
    }

    @Test
    void useAndManageGoThroughTheMatchingGateWithTheSwitch() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofMinutes(30)));
        when(access.manage(member, "google", true, true)).thenReturn(granted(Duration.ofMinutes(30)));

        assertThat(tokens.forUse(member).accessToken()).isEqualTo(OLD_ACCESS);
        assertThat(tokens.forManage(member).accessToken()).isEqualTo(OLD_ACCESS);

        verify(access).use(member, "google", true);
        verify(access).manage(member, "google", true, true);
    }

    @Test
    void aTokenCloseToExpiryIsRenewedOnceAndTheNewOneIsStoredAndReturned() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofSeconds(60)));
        when(api.refresh(REFRESH)).thenReturn(refreshed(null));
        when(credentials.refreshTokens(any(), any(), any(), any(), any(), any())).thenReturn(true);

        GoogleAccess result = tokens.forUse(member);

        assertThat(result.accessToken()).isEqualTo("new-access-secret");
        verify(api).refresh(REFRESH);
        verify(credentials).refreshTokens(member, "google", "new-access-secret", null, NOW.plusSeconds(3599), null);
    }

    @Test
    void aNewRefreshTokenFromGoogleIsPassedThrough() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofSeconds(60)));
        when(api.refresh(REFRESH)).thenReturn(refreshed("rotated-refresh"));
        when(credentials.refreshTokens(any(), any(), any(), any(), any(), any())).thenReturn(true);

        tokens.forUse(member);

        verify(credentials).refreshTokens(member, "google", "new-access-secret", "rotated-refresh",
                NOW.plusSeconds(3599), null);
    }

    @Test
    void aFreshTokenIsNotRenewed() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofMinutes(30)));

        tokens.forUse(member);

        verifyNoInteractions(api);
        verify(credentials, never()).refreshTokens(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRenewalThatLosesToADisconnectFailsAsNotConnected() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofSeconds(60)));
        when(api.refresh(REFRESH)).thenReturn(refreshed(null));
        when(credentials.refreshTokens(any(), any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> tokens.forUse(member)).isInstanceOfSatisfying(ConnectorException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONNECTED));
    }

    @Test
    void aRefusedRefreshTokenMarksTheConnectionRevokedAndAsksToReconnect() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofSeconds(60)));
        when(api.refresh(REFRESH)).thenReturn(refused(400, "invalid_grant"));

        assertThatThrownBy(() -> tokens.forUse(member)).isInstanceOfSatisfying(ConnectorException.class, e -> {
            assertThat(e.reason()).isEqualTo(Reason.NOT_CONNECTED);
            assertThat(e.detail().code()).isEqualTo("connector.google.reconnect");
            assertThat(e.providerReason()).isEqualTo("reconnect");
            assertThat(e.getMessage() + e).doesNotContain(REFRESH).doesNotContain(OLD_ACCESS);
        });

        verify(credentials).markRevoked(member, "google");
        verify(credentials, never()).refreshTokens(any(), any(), any(), any(), any(), any());
    }

    @Test
    void renewNowRenewsAFreshTokenAndHandlesARefusalTheSameWay() {
        when(access.manage(member, "google", true, true)).thenReturn(granted(Duration.ofMinutes(30)));
        when(api.refresh(REFRESH)).thenReturn(refreshed(null));
        when(credentials.refreshTokens(any(), any(), any(), any(), any(), any())).thenReturn(true);

        assertThat(tokens.renewNow(member).accessToken()).isEqualTo("new-access-secret");
        verify(credentials).refreshTokens(member, "google", "new-access-secret", null, NOW.plusSeconds(3599), null);

        when(api.refresh(REFRESH)).thenReturn(refused(400, "invalid_grant"));

        assertThatThrownBy(() -> tokens.renewNow(member)).isInstanceOfSatisfying(ConnectorException.class,
                e -> assertThat(e.providerReason()).isEqualTo("reconnect"));
        verify(credentials).markRevoked(member, "google");
    }

    @Test
    void anUnreachableGoogleLeavesTheConnectionAlone() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofSeconds(60)));
        ConnectorException unreachable = ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE);
        when(api.refresh(anyString())).thenThrow(unreachable);

        assertThatThrownBy(() -> tokens.forUse(member)).isSameAs(unreachable);

        verify(credentials, never()).markRevoked(any(), any());
        verify(credentials, never()).refreshTokens(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anotherRefusalOfTheRefreshIsClassifiedAndLeavesTheConnectionAlone() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofSeconds(60)));
        when(api.refresh(REFRESH)).thenReturn(refused(503, "backendError"));

        assertThatThrownBy(() -> tokens.forUse(member)).isInstanceOfSatisfying(ConnectorException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE));

        verify(credentials, never()).markRevoked(any(), any());
    }

    @Test
    void aSwitchedOffConnectorFailsInTheGateAndGoogleIsNeverCalled() {
        GoogleProperties off = new GoogleProperties(false, null, null, null, null, null, null, null, null, null);
        GoogleTokens disabled = new GoogleTokens(access, credentials, api, off, Clock.fixed(NOW, ZoneOffset.UTC));
        ConnectorException turnedOff = ConnectorException.of(Reason.TURNED_OFF);
        when(access.use(eq(member), eq("google"), eq(false))).thenThrow(turnedOff);

        assertThatThrownBy(() -> disabled.forUse(member)).isSameAs(turnedOff);

        verifyNoInteractions(api);
    }

    @Test
    void theAccessHandleNeverPrintsTheToken() {
        when(access.use(member, "google", true)).thenReturn(granted(Duration.ofMinutes(30)));

        assertThat(tokens.forUse(member).toString()).doesNotContain(OLD_ACCESS);
    }
}
