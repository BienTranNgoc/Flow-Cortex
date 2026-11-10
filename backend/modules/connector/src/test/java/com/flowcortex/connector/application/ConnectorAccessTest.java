package com.flowcortex.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorCredentials;
import com.flowcortex.connector.api.ConnectorCredentials.ActiveCredential;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.api.CredentialUnreadableException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.UserDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class ConnectorAccessTest {

    private static final String PROVIDER = "telegram";

    private final UserDirectory users = mock(UserDirectory.class);
    private final ConnectorCredentials credentials = mock(ConnectorCredentials.class);
    private final ConnectorAccess access = new ConnectorAccess(users, credentials);
    private final UUID member = UUID.randomUUID();

    private static ConnectorConnection connection(boolean active, boolean usageEnabled) {
        return new ConnectorConnection(PROVIDER, List.of(), Map.of(), null, active, usageEnabled, null, Instant.EPOCH,
                Instant.EPOCH);
    }

    private void memberExists() {
        when(users.findById(member)).thenReturn(Optional.of(
                new CurrentUser(member, member, "m@example.com", "M", "MEMBER", "ACTIVE", true, false)));
    }

    @Test
    void aSuspendedMemberFailsThroughTheGateAndNoCredentialIsTouched() {
        when(users.findById(member)).thenThrow(new AccessDeniedException("suspended"));

        assertThatThrownBy(() -> access.use(member, PROVIDER, true))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.reason()).isEqualTo(ConnectorException.Reason.SUSPENDED));

        verify(credentials, never()).find(any(), any());
        verify(credentials, never()).readTokens(any(), any());
    }

    @Test
    void aSwitchedOffConnectorTouchesNoCredential() {
        memberExists();

        assertThatThrownBy(() -> access.use(member, PROVIDER, false))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.reason()).isEqualTo(ConnectorException.Reason.TURNED_OFF));

        verifyNoInteractions(credentials);
    }

    @Test
    void aMissingMemberIsNotFound() {
        when(users.findById(member)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.use(member, PROVIDER, true)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void useReturnsTheConnectionAndTheTokens() {
        memberExists();
        ConnectorTokens tokens = new ConnectorTokens("secret", null, null, List.of());
        ConnectorConnection view = connection(true, true);
        when(credentials.readActive(member, PROVIDER)).thenReturn(Optional.of(new ActiveCredential(view, () -> tokens)));

        ConnectorAccess.Granted granted = access.use(member, PROVIDER, true);

        assertThat(granted.tokens()).isSameAs(tokens);
        assertThat(granted.connection()).isSameAs(view);
        verify(credentials, never()).find(any(), any());
        verify(credentials, never()).readTokens(any(), any());
    }

    @Test
    void manageWithoutTokensNeverReadsThem() {
        memberExists();
        when(credentials.find(member, PROVIDER)).thenReturn(Optional.of(connection(true, false)));

        ConnectorAccess.Granted granted = access.manage(member, PROVIDER, true, false);

        assertThat(granted.tokens()).isNull();
        verify(credentials, never()).readTokens(any(), any());
    }

    @Test
    void anInactiveConnectionIsNotConnected() {
        memberExists();
        when(credentials.readActive(member, PROVIDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.manage(member, PROVIDER, true, true))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.reason()).isEqualTo(ConnectorException.Reason.NOT_CONNECTED));
    }

    private static ConnectorCredentials.SealedTokens unreadable(AtomicInteger opened) {
        return () -> {
            opened.incrementAndGet();
            throw new CredentialUnreadableException("bad");
        };
    }

    @Test
    void anAllowedCallOnAnUnreadableCredentialAsksForAReconnect() {
        memberExists();
        AtomicInteger opened = new AtomicInteger();
        when(credentials.readActive(member, PROVIDER))
                .thenReturn(Optional.of(new ActiveCredential(connection(true, true), unreadable(opened))));

        assertThatThrownBy(() -> access.use(member, PROVIDER, true))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.reason()).isEqualTo(ConnectorException.Reason.CREDENTIAL_UNREADABLE);
                    assertThat(e.detail().code()).isEqualTo("connector.credentialUnreadable");
                });
        assertThat(opened).hasValue(1);
    }

    @Test
    void usageOffWithAnUnreadableCredentialAnswersUsageDisabledWithoutDecrypting() {
        memberExists();
        AtomicInteger opened = new AtomicInteger();
        when(credentials.readActive(member, PROVIDER))
                .thenReturn(Optional.of(new ActiveCredential(connection(true, false), unreadable(opened))));

        assertThatThrownBy(() -> access.use(member, PROVIDER, true))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.reason()).isEqualTo(ConnectorException.Reason.USAGE_DISABLED));
        assertThat(opened).hasValue(0);
    }

    @Test
    void manageWithTokensOnAnUnreadableCredentialAsksForAReconnectWhenUsageIsOff() {
        memberExists();
        AtomicInteger opened = new AtomicInteger();
        when(credentials.readActive(member, PROVIDER))
                .thenReturn(Optional.of(new ActiveCredential(connection(true, false), unreadable(opened))));

        assertThatThrownBy(() -> access.manage(member, PROVIDER, true, true))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.reason()).isEqualTo(ConnectorException.Reason.CREDENTIAL_UNREADABLE));
        assertThat(opened).hasValue(1);
    }

    @Test
    void aRowRevokedBetweenTheChecksIsNotConnected() {
        memberExists();
        when(credentials.readActive(member, PROVIDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.use(member, PROVIDER, true))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.reason()).isEqualTo(ConnectorException.Reason.NOT_CONNECTED));
    }

    @Test
    void requireMemberChecksTheMemberAndTheSwitchOnly() {
        memberExists();

        access.requireMember(member, true);

        assertThatThrownBy(() -> access.requireMember(member, false))
                .isInstanceOfSatisfying(ConnectorException.class,
                        e -> assertThat(e.reason()).isEqualTo(ConnectorException.Reason.TURNED_OFF));
        verifyNoInteractions(credentials);
    }
}
