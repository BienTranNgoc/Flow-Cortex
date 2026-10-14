package com.flowcortex.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    @Test
    void newTokenIsUsableBeforeExpiry() {
        RefreshToken token = newToken(NOW.plusSeconds(3600));
        assertThat(token.isUsable(NOW)).isTrue();
        assertThat(token.isExpired(NOW)).isFalse();
    }

    @Test
    void expiredTokenIsNotUsable() {
        RefreshToken token = newToken(NOW.minusSeconds(1));
        assertThat(token.isExpired(NOW)).isTrue();
        assertThat(token.isUsable(NOW)).isFalse();
    }

    @Test
    void usedTokenIsNotUsable() {
        RefreshToken token = newToken(NOW.plusSeconds(3600));
        token.markUsed(NOW, UUID.randomUUID());
        assertThat(token.isUsable(NOW)).isFalse();
        assertThat(token.getUsedAt()).isEqualTo(NOW);
    }

    @Test
    void revokedTokenIsNotUsable() {
        RefreshToken token = newToken(NOW.plusSeconds(3600));
        token.revoke(NOW);
        assertThat(token.isUsable(NOW)).isFalse();
        assertThat(token.getRevokedAt()).isEqualTo(NOW);
    }

    @Test
    void revokeOverwritesPreviousRevokeAt() {
        RefreshToken token = newToken(NOW.plusSeconds(3600));
        token.revoke(NOW);
        token.revoke(NOW.plusSeconds(60));
        assertThat(token.getRevokedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    private static RefreshToken newToken(Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), UUID.randomUUID(), "h", UUID.randomUUID(), NOW, expiresAt, false,
                "ip", "ua");
    }
}
