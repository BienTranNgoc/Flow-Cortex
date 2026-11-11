package com.flowcortex.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.connector.api.ConnectorConnection;
import com.flowcortex.connector.api.ConnectorTokens;
import com.flowcortex.connector.api.CredentialUnreadableException;
import com.flowcortex.connector.domain.ConnectorAuth;
import com.flowcortex.connector.infrastructure.ConnectorAuthRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConnectorCredentialsImplTest {

    private static final UUID MEMBER = UUID.randomUUID();
    private static final String PROVIDER = "google";
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(3600);

    private final ConnectorAuthRepository repository = mock(ConnectorAuthRepository.class);
    private final CredentialCipher cipher = ConnectorEncryptionConfig.buildCipher("k1", Map.of("k1", newKey()));
    private final ConnectorCredentialsImpl credentials =
            new ConnectorCredentialsImpl(repository, cipher, Clock.fixed(NOW, ZoneOffset.UTC));

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private ConnectorAuth row(boolean active, String access, String refresh, String metadata) {
        ConnectorAuth row = mock(ConnectorAuth.class);
        when(row.getProvider()).thenReturn(PROVIDER);
        when(row.isActive()).thenReturn(active);
        when(row.isUsageEnabled()).thenReturn(true);
        when(row.getAccessToken()).thenReturn(access);
        when(row.getRefreshToken()).thenReturn(refresh);
        when(row.getExpiresAt()).thenReturn(EXPIRES);
        when(row.getScopes()).thenReturn(List.of("mail.read"));
        when(row.getMetadata()).thenReturn(metadata);
        when(row.getCreatedAt()).thenReturn(NOW);
        when(row.getUpdatedAt()).thenReturn(NOW);
        return row;
    }

    @Test
    void connectStoresCiphertextBoundToTheMemberAndProvider() {
        credentials.connect(MEMBER, PROVIDER, new ConnectorTokens("access-1", "refresh-1", EXPIRES, List.of("a", "b")),
                Map.of("email", "m@example.com"));

        ArgumentCaptor<String> access = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refresh = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String[]> scopes = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(any(UUID.class), eq(MEMBER), eq(PROVIDER), access.capture(), refresh.capture(),
                eq(EXPIRES), scopes.capture(), metadata.capture(), eq(NOW));
        assertThat(access.getValue()).doesNotContain("access-1");
        assertThat(cipher.decrypt(MEMBER, PROVIDER, access.getValue())).isEqualTo("access-1");
        assertThat(cipher.decrypt(MEMBER, PROVIDER, refresh.getValue())).isEqualTo("refresh-1");
        assertThat(scopes.getValue()).containsExactly("a", "b");
        assertThat(metadata.getValue()).isEqualTo("{\"email\":\"m@example.com\"}");
    }

    @Test
    void connectWithoutRefreshScopesOrMetadataStoresEmptyDefaults() {
        credentials.connect(MEMBER, PROVIDER, new ConnectorTokens("access-1", null, null, null), null);

        ArgumentCaptor<String[]> scopes = ArgumentCaptor.forClass(String[].class);
        verify(repository).upsert(any(UUID.class), eq(MEMBER), eq(PROVIDER), anyString(), isNull(), isNull(),
                scopes.capture(), eq("{}"), eq(NOW));
        assertThat(scopes.getValue()).isEmpty();
    }

    @Test
    void anInvalidProviderIsRejectedBeforeTheRepository() {
        ConnectorTokens tokens = new ConnectorTokens("a", null, null, List.of());
        assertThatThrownBy(() -> credentials.connect(MEMBER, "Google", tokens, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credentials.find(MEMBER, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credentials.readTokens(MEMBER, "1abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credentials.disconnect(MEMBER, "")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void listPicksTheActiveOnlyQueryUnlessInactiveIsAskedFor() {
        ConnectorAuth row = row(true, "x", null, "{\"email\":\"m@example.com\"}");
        when(repository.findByUserIdAndActiveTrueOrderByProvider(MEMBER)).thenReturn(List.of(row));
        when(repository.findByUserIdOrderByProvider(MEMBER)).thenReturn(List.of());

        List<ConnectorConnection> active = credentials.list(MEMBER, false);
        assertThat(active).singleElement().satisfies(view -> {
            assertThat(view.provider()).isEqualTo(PROVIDER);
            assertThat(view.scopes()).containsExactly("mail.read");
            assertThat(view.metadata()).containsEntry("email", "m@example.com");
            assertThat(view.active()).isTrue();
            assertThat(view.usageEnabled()).isTrue();
            assertThat(view.expiresAt()).isEqualTo(EXPIRES);
        });
        assertThat(credentials.list(MEMBER, true)).isEmpty();
    }

    @Test
    void findMapsABlankMetadataToAnEmptyMap() {
        ConnectorAuth row = row(false, "x", null, " ");
        when(repository.findByUserIdAndProvider(MEMBER, PROVIDER)).thenReturn(Optional.of(row));

        assertThat(credentials.find(MEMBER, PROVIDER)).hasValueSatisfying(view -> {
            assertThat(view.metadata()).isEmpty();
            assertThat(view.active()).isFalse();
        });
    }

    @Test
    void readTokensDecryptsAnActiveConnection() {
        ConnectorAuth row = row(true, cipher.encrypt(MEMBER, PROVIDER, "access-1"),
                cipher.encrypt(MEMBER, PROVIDER, "refresh-1"), "{}");
        when(repository.findByUserIdAndProvider(MEMBER, PROVIDER)).thenReturn(Optional.of(row));

        assertThat(credentials.readTokens(MEMBER, PROVIDER)).hasValueSatisfying(tokens -> {
            assertThat(tokens.accessToken()).isEqualTo("access-1");
            assertThat(tokens.refreshToken()).isEqualTo("refresh-1");
            assertThat(tokens.expiresAt()).isEqualTo(EXPIRES);
            assertThat(tokens.scopes()).containsExactly("mail.read");
        });
    }

    @Test
    void readTokensKeepsAMissingRefreshTokenNull() {
        ConnectorAuth row = row(true, cipher.encrypt(MEMBER, PROVIDER, "access-1"), null, "{}");
        when(repository.findByUserIdAndProvider(MEMBER, PROVIDER)).thenReturn(Optional.of(row));

        assertThat(credentials.readTokens(MEMBER, PROVIDER))
                .hasValueSatisfying(tokens -> assertThat(tokens.refreshToken()).isNull());
    }

    @Test
    void readTokensIsEmptyForAnInactiveOrMissingConnection() {
        ConnectorAuth row = row(false, cipher.encrypt(MEMBER, PROVIDER, "access-1"), null, "{}");
        when(repository.findByUserIdAndProvider(MEMBER, PROVIDER)).thenReturn(Optional.of(row));
        when(repository.findByUserIdAndProvider(MEMBER, "slack")).thenReturn(Optional.empty());

        assertThat(credentials.readTokens(MEMBER, PROVIDER)).isEmpty();
        assertThat(credentials.readTokens(MEMBER, "slack")).isEmpty();
    }

    @Test
    void readTokensRefusesCiphertextBoundToAnotherMember() {
        ConnectorAuth row = row(true, cipher.encrypt(UUID.randomUUID(), PROVIDER, "access-1"), null, "{}");
        when(repository.findByUserIdAndProvider(MEMBER, PROVIDER)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> credentials.readTokens(MEMBER, PROVIDER))
                .isInstanceOf(CredentialUnreadableException.class)
                .hasMessageNotContaining("access-1");
    }

    @Test
    void refreshTokensEncryptsAndKeepsNullsForTheStoredValues() {
        when(repository.refresh(eq(MEMBER), eq(PROVIDER), anyString(), isNull(), eq(EXPIRES), isNull(), eq(NOW)))
                .thenReturn(1);

        assertThat(credentials.refreshTokens(MEMBER, PROVIDER, "access-2", null, EXPIRES, null)).isTrue();

        ArgumentCaptor<String> access = ArgumentCaptor.forClass(String.class);
        verify(repository).refresh(eq(MEMBER), eq(PROVIDER), access.capture(), isNull(), eq(EXPIRES), isNull(),
                eq(NOW));
        assertThat(cipher.decrypt(MEMBER, PROVIDER, access.getValue())).isEqualTo("access-2");
    }

    @Test
    void refreshTokensReportsNothingChangedOnAnInactiveRow() {
        when(repository.refresh(any(), any(), anyString(), anyString(), any(), any(), any())).thenReturn(0);

        assertThat(credentials.refreshTokens(MEMBER, PROVIDER, "access-2", "refresh-2", EXPIRES, List.of("a")))
                .isFalse();
    }

    @Test
    void stateChangesReportWhetherARowChanged() {
        when(repository.revoke(MEMBER, PROVIDER, NOW)).thenReturn(1, 0);
        when(repository.setUsage(eq(MEMBER), eq(PROVIDER), anyBoolean(), eq(NOW))).thenReturn(1, 0);
        when(repository.deleteByUserAndProvider(MEMBER, PROVIDER)).thenReturn(1, 0);

        assertThat(credentials.markRevoked(MEMBER, PROVIDER)).isTrue();
        assertThat(credentials.markRevoked(MEMBER, PROVIDER)).isFalse();
        assertThat(credentials.setUsageEnabled(MEMBER, PROVIDER, false)).isTrue();
        assertThat(credentials.setUsageEnabled(MEMBER, PROVIDER, true)).isFalse();
        assertThat(credentials.disconnect(MEMBER, PROVIDER)).isTrue();
        assertThat(credentials.disconnect(MEMBER, PROVIDER)).isFalse();
    }

    @Test
    void tokensToStringOmitsTheTokens() {
        assertThat(new ConnectorTokens("access-1", "refresh-1", EXPIRES, List.of("a")).toString())
                .doesNotContain("access-1", "refresh-1")
                .contains("a");
    }
}
