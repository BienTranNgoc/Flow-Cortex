package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.RefreshToken;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class JwtRefreshTokenServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.now(FIXED);
    private static final Duration TTL = Duration.ofDays(7);

    private final RefreshTokenRepository refreshTokenRepository = Mockito.mock(RefreshTokenRepository.class);
    private final AppUserRepository userRepository = Mockito.mock(AppUserRepository.class);
    private final AccessTokenService accessTokenService = Mockito.mock(AccessTokenService.class);
    private final AuditEventWriter audit = Mockito.mock(AuditEventWriter.class);
    private final RefreshTokenProperties properties = new RefreshTokenProperties(TTL, Duration.ofDays(30), 5);

    private final JwtRefreshTokenService service = new JwtRefreshTokenService(refreshTokenRepository, userRepository,
            accessTokenService, audit, properties, FIXED);

    @Test
    void issueForPersistsHashedTokenAndReturnsPair() {
        AppUser user = activeUser();
        when(refreshTokenRepository.countActiveByUserId(user.getId(), NOW)).thenReturn(0);
        when(refreshTokenRepository.findActiveByUserIdAndFamilyId(any(), any())).thenReturn(List.of());
        when(accessTokenService.mint(user)).thenReturn("signed-access-jwt");
        when(accessTokenService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        TokenPair pair = service.issueFor(user, "10.0.0.1", "ua", false);

        assertThat(pair.accessToken()).isEqualTo("signed-access-jwt");
        assertThat(pair.refreshToken()).isNotEmpty();
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        assertThat(saved.getCreatedIp()).isEqualTo("10.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("ua");
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        // The stored hash is the SHA-256 of the raw token, never the raw itself.
        assertThat(saved.getTokenHash()).hasSize(64);
        assertThat(saved.getTokenHash()).isNotEqualTo(pair.refreshToken());
        verify(audit).write(any(), any(), any(), any(), any());
    }

    @Test
    void issueForTruncatesLongUserAgentToFiveTwelveBytes() {
        AppUser user = activeUser();
        when(refreshTokenRepository.countActiveByUserId(user.getId(), NOW)).thenReturn(0);
        when(refreshTokenRepository.findActiveByUserIdAndFamilyId(any(), any())).thenReturn(List.of());
        when(accessTokenService.mint(user)).thenReturn("a");
        when(accessTokenService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        String longUa = "x".repeat(2000);

        service.issueFor(user, "ip", longUa, false);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserAgent()).hasSize(512);
    }

    @Test
    void issueForUsesRememberMeTtlWhenRememberMeIsTrue() {
        AppUser user = activeUser();
        when(refreshTokenRepository.countActiveByUserId(user.getId(), NOW)).thenReturn(0);
        when(refreshTokenRepository.findActiveByUserIdAndFamilyId(any(), any())).thenReturn(List.of());
        when(accessTokenService.mint(user)).thenReturn("a");
        when(accessTokenService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

        service.issueFor(user, "ip", "ua", true);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void rotateUnknownTokenThrowsReuseDetected() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("raw", "ip", "ua"))
                .isInstanceOf(ReuseDetectedException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotateExpiredTokenRevokesFamilyAndThrowsReuse() {
        AppUser user = activeUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken expired = new RefreshToken(UUID.randomUUID(), user.getId(), "h", familyId,
                NOW.minusSeconds(3600), NOW.minusSeconds(60), "ip", "ua");

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.rotate("raw", "ip", "ua"))
                .isInstanceOf(ReuseDetectedException.class);
        verify(refreshTokenRepository).revokeFamily(familyId, NOW);
    }

    @Test
    void rotateUsedTokenRevokesFamilyAndThrowsReuse() {
        AppUser user = activeUser();
        UUID familyId = UUID.randomUUID();
        UUID replacedBy = UUID.randomUUID();
        RefreshToken used = new RefreshToken(UUID.randomUUID(), user.getId(), "h", familyId,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), "ip", "ua");
        used.markUsed(NOW.minusSeconds(30), replacedBy);

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(used));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.rotate("raw", "ip", "ua"))
                .isInstanceOf(ReuseDetectedException.class);
        verify(refreshTokenRepository).revokeFamily(familyId, NOW);
    }

    @Test
    void rotateHappyPathIssuesNewPairAndMarksOldUsed() {
        AppUser user = activeUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken oldToken = new RefreshToken(UUID.randomUUID(), user.getId(), "h", familyId,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), "old-ip", "old-ua");

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(oldToken));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(accessTokenService.mint(user)).thenReturn("new-access-jwt");
        when(accessTokenService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenRepository.markUsed(Mockito.eq(oldToken.getId()), Mockito.eq(NOW), any(UUID.class)))
                .thenReturn(1);

        TokenPair pair = service.rotate("raw", "new-ip", "new-ua");

        assertThat(pair.accessToken()).isEqualTo("new-access-jwt");
        assertThat(pair.refreshToken()).isNotEmpty();
        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(savedCaptor.capture());
        RefreshToken saved = savedCaptor.getValue();
        assertThat(saved.getFamilyId()).isEqualTo(familyId);
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        verify(refreshTokenRepository).markUsed(Mockito.eq(oldToken.getId()), Mockito.eq(NOW), any(UUID.class));
    }

    @Test
    void rotateLosingCasRaceRevokesFamilyAndThrowsReuse() {
        AppUser user = activeUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken oldToken = new RefreshToken(UUID.randomUUID(), user.getId(), "h", familyId,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), "ip", "ua");

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(oldToken));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.markUsed(Mockito.eq(oldToken.getId()), Mockito.eq(NOW), any(UUID.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.rotate("raw", "ip", "ua"))
                .isInstanceOf(ReuseDetectedException.class);
        verify(refreshTokenRepository).revokeFamily(familyId, NOW);
    }

    @Test
    void revokeFamilyDelegatesToRepository() {
        UUID familyId = UUID.randomUUID();
        service.revokeFamily(familyId, NOW);
        verify(refreshTokenRepository).revokeFamily(familyId, NOW);
    }

    @Test
    void findByRawLooksUpByHash() {
        UUID familyId = UUID.randomUUID();
        AppUser user = activeUser();
        String raw = "raw-input-token";
        String hash = RefreshTokenHasher.hash(raw);
        RefreshToken stored = new RefreshToken(UUID.randomUUID(), user.getId(), hash, familyId,
                NOW, NOW.plusSeconds(3600), "ip", "ua");
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));

        Optional<RefreshToken> result = service.findByRaw(raw);

        assertThat(result).hasValue(stored);
    }

    private static AppUser activeUser() {
        UUID id = UUID.randomUUID();
        return new AppUser(id, id, "person@example.com", "hash", "Person", Role.MEMBER, UserStatus.ACTIVE);
    }
}
