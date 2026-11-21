package com.flowcortex.user.pat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.RefreshTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class DefaultAccountCredentialsTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);
    private final PersonalAccessTokenRepository tokens = mock(PersonalAccessTokenRepository.class);
    private final AuditEventWriter audit = mock(AuditEventWriter.class);
    private final DefaultAccountCredentials credentials = new DefaultAccountCredentials(refreshTokens, tokens, audit,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    private PersonalAccessToken live(String name) {
        return new PersonalAccessToken(UUID.randomUUID(), userId, name, "hash-" + name, NOW.minusSeconds(60),
                NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void revokesSessionsAndEveryLiveTokenAndAuditsEachWithTheReason() {
        PersonalAccessToken first = live("ci");
        PersonalAccessToken second = live("deploy");
        when(tokens.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId)).thenReturn(List.of(first, second));

        credentials.revokeAllFor(userId);

        InOrder order = inOrder(refreshTokens, tokens);
        order.verify(refreshTokens).revokeAllFor(userId);
        order.verify(tokens).revokeAllByUserId(userId, NOW);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit, org.mockito.Mockito.times(2)).write(eq(AuthEventType.PAT_REVOKED), eq(userId), any(), any(),
                metadata.capture());
        assertThat(metadata.getAllValues()).extracting(m -> m.get("tokenId"))
                .containsExactly(first.getId().toString(), second.getId().toString());
        assertThat(metadata.getAllValues()).allSatisfy(m -> {
            assertThat(m.get("reason")).isEqualTo("credentials_reset");
            assertThat(m).doesNotContainKey("token");
        });
    }

    @Test
    void anAccountWithoutTokensStillEndsItsSessionsAndWritesNoAuditRow() {
        when(tokens.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        credentials.revokeAllFor(userId);

        verify(refreshTokens).revokeAllFor(userId);
        verify(tokens, never()).revokeAllByUserId(any(), any());
        verifyNoInteractions(audit);
    }
}
