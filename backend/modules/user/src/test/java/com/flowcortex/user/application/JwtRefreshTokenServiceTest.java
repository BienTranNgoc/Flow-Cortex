package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.user.api.AuthSession;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
    private final RefreshTokenProperties properties = new RefreshTokenProperties(TTL, Duration.ofDays(30), 2);

    private final JwtRefreshTokenService service = new JwtRefreshTokenService(refreshTokenRepository, userRepository,
            accessTokenService, properties, FIXED);

    @BeforeEach
    void accessTokenLivesFifteenMinutes() {
        when(accessTokenService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
    }

    @Test
    void issueForPersistsHashedTokenAndReturnsSession() {
        AppUser user = activeUser();
        when(accessTokenService.mint(user)).thenReturn("signed-access-jwt");

        AuthSession session = service.issueFor(user, "10.0.0.1", "ua", false);

        assertThat(session.accessToken()).isEqualTo("signed-access-jwt");
        assertThat(session.expiresIn()).isEqualTo(900L);
        assertThat(session.refreshToken()).isNotEmpty();
        assertThat(session.persistent()).isFalse();
        assertThat(session.refreshTokenExpiresAt()).isEqualTo(NOW.plus(TTL));
        RefreshToken saved = savedToken();
        assertThat(saved.getUserId()).isEqualTo(user.getId());
        assertThat(saved.getCreatedIp()).isEqualTo("10.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("ua");
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(saved.isRememberMe()).isFalse();
        // The stored hash is the SHA-256 of the raw token, never the raw itself.
        assertThat(saved.getTokenHash()).hasSize(64);
        assertThat(saved.getTokenHash()).isNotEqualTo(session.refreshToken());
    }

    @Test
    void issueForTruncatesLongUserAgentToFiveTwelveBytes() {
        service.issueFor(activeUser(), "ip", "x".repeat(2000), false);

        assertThat(savedToken().getUserAgent()).hasSize(512);
    }

    @Test
    void issueForUsesRememberMeTtlAndFlagsTheSessionPersistentWhenRememberMeIsTrue() {
        AuthSession session = service.issueFor(activeUser(), "ip", "ua", true);

        assertThat(savedToken().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(savedToken().isRememberMe()).isTrue();
        assertThat(session.persistent()).isTrue();
        assertThat(session.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void issueForRevokesTheOldestFamiliesSoTheNewSessionFitsTheCap() {
        AppUser user = activeUser();
        List<RefreshToken> active = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            active.add(token(user, UUID.randomUUID(), NOW.minusSeconds(100L - i), NOW.plusSeconds(3600)));
        }
        when(refreshTokenRepository.findActiveByUserId(user.getId(), NOW)).thenReturn(active);

        service.issueFor(user, "ip", "ua", false);

        // 3 live sessions with a cap of 2: two go so that the new one is the second.
        verify(refreshTokenRepository).revokeFamily(active.get(0).getFamilyId(), NOW);
        verify(refreshTokenRepository).revokeFamily(active.get(1).getFamilyId(), NOW);
        verify(refreshTokenRepository, never()).revokeFamily(active.get(2).getFamilyId(), NOW);
    }

    @Test
    void issueForRevokesNothingBelowTheCap() {
        AppUser user = activeUser();
        when(refreshTokenRepository.findActiveByUserId(user.getId(), NOW))
                .thenReturn(List.of(token(user, UUID.randomUUID(), NOW.minusSeconds(10), NOW.plusSeconds(3600))));

        service.issueFor(user, "ip", "ua", false);

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void rotateUnknownTokenIsRejectedWithoutWriting() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        RotationResult result = service.rotate("raw", "ip", "ua");

        assertThat(result).isEqualTo(new RotationResult.Rejected(RotationResult.Reason.UNKNOWN_TOKEN, null, null));
        verify(refreshTokenRepository, never()).save(any());
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void rotateExpiredTokenIsRejected() {
        AppUser user = activeUser();
        RefreshToken expired = token(user, UUID.randomUUID(), NOW.minusSeconds(3600), NOW.minusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        RotationResult result = service.rotate("raw", "ip", "ua");

        assertThat(result).isEqualTo(new RotationResult.Rejected(RotationResult.Reason.EXPIRED, user.getId(),
                expired.getFamilyId()));
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotateRevokedTokenIsRejectedWithoutTreatingItAsTheft() {
        AppUser user = activeUser();
        RefreshToken revoked = token(user, UUID.randomUUID(), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        revoked.revoke(NOW.minusSeconds(30));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));

        RotationResult result = service.rotate("raw", "ip", "ua");

        assertThat(result).isEqualTo(new RotationResult.Rejected(RotationResult.Reason.REVOKED, user.getId(),
                revoked.getFamilyId()));
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void rotateUsedTokenRevokesTheFamilyAndReportsReuseAsAResult() {
        AppUser user = activeUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken used = token(user, familyId, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        used.markUsed(NOW.minusSeconds(30), UUID.randomUUID());
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(used));

        RotationResult result = service.rotate("raw", "ip", "ua");

        assertThat(result).isEqualTo(new RotationResult.Rejected(RotationResult.Reason.REUSED, user.getId(),
                familyId));
        verify(refreshTokenRepository).revokeFamily(familyId, NOW);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void rotateForASuspendedAccountRevokesTheFamilyAndIsRejected() {
        AppUser user = activeUser();
        user.changeStatus(UserStatus.SUSPENDED);
        UUID familyId = UUID.randomUUID();
        RefreshToken live = token(user, familyId, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(live));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        RotationResult result = service.rotate("raw", "ip", "ua");

        assertThat(result).isEqualTo(new RotationResult.Rejected(RotationResult.Reason.ACCOUNT_NOT_ALLOWED,
                user.getId(), familyId));
        verify(refreshTokenRepository).revokeFamily(familyId, NOW);
        verify(accessTokenService, never()).mint(any());
    }

    @Test
    void rotateForALockedAccountIsRejected() {
        AppUser user = activeUser();
        user.recordFailedLogin(1, Duration.ofMinutes(15), NOW);
        RefreshToken live = token(user, UUID.randomUUID(), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(live));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        RotationResult result = service.rotate("raw", "ip", "ua");

        assertThat(result).isInstanceOfSatisfying(RotationResult.Rejected.class,
                rejected -> assertThat(rejected.reason()).isEqualTo(RotationResult.Reason.ACCOUNT_NOT_ALLOWED));
    }

    @Test
    void rotateHappyPathKeepsTheFamilyExpiryAndRememberMeAndMarksTheOldTokenUsed() {
        AppUser user = activeUser();
        UUID familyId = UUID.randomUUID();
        Instant familyExpiry = NOW.plus(Duration.ofDays(3));
        RefreshToken oldToken = new RefreshToken(UUID.randomUUID(), user.getId(), "h", familyId,
                NOW.minusSeconds(60), familyExpiry, true, "old-ip", "old-ua");
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(oldToken));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(accessTokenService.mint(user)).thenReturn("new-access-jwt");
        when(refreshTokenRepository.markUsed(Mockito.eq(oldToken.getId()), Mockito.eq(NOW), any(UUID.class)))
                .thenReturn(1);

        RotationResult result = service.rotate("raw", "new-ip", "new-ua");

        assertThat(result).isInstanceOfSatisfying(RotationResult.Rotated.class, rotated -> {
            assertThat(rotated.session().accessToken()).isEqualTo("new-access-jwt");
            assertThat(rotated.session().refreshToken()).isNotEmpty();
            assertThat(rotated.session().refreshTokenExpiresAt()).isEqualTo(familyExpiry);
            assertThat(rotated.session().persistent()).isTrue();
            assertThat(rotated.userId()).isEqualTo(user.getId());
            assertThat(rotated.familyId()).isEqualTo(familyId);
        });
        RefreshToken saved = savedToken();
        assertThat(saved.getFamilyId()).isEqualTo(familyId);
        assertThat(saved.getExpiresAt()).isEqualTo(familyExpiry);
        assertThat(saved.isRememberMe()).isTrue();
        verify(refreshTokenRepository).markUsed(Mockito.eq(oldToken.getId()), Mockito.eq(NOW), any(UUID.class));
    }

    @Test
    void rotateLosingTheCasRaceRevokesTheFamilyAndReportsReuse() {
        AppUser user = activeUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken oldToken = token(user, familyId, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(oldToken));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokenRepository.markUsed(Mockito.eq(oldToken.getId()), Mockito.eq(NOW), any(UUID.class)))
                .thenReturn(0);

        RotationResult result = service.rotate("raw", "ip", "ua");

        assertThat(result).isEqualTo(new RotationResult.Rejected(RotationResult.Reason.REUSED, user.getId(),
                familyId));
        verify(refreshTokenRepository).revokeFamily(familyId, NOW);
    }

    @Test
    void endSessionRevokesTheTokensFamilyAndReturnsItsUser() {
        AppUser user = activeUser();
        UUID familyId = UUID.randomUUID();
        String raw = "raw-input-token";
        RefreshToken stored = token(user, familyId, NOW, NOW.plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(RefreshTokenHasher.hash(raw))).thenReturn(Optional.of(stored));

        Optional<UUID> owner = service.endSession(raw);

        assertThat(owner).hasValue(user.getId());
        verify(refreshTokenRepository).revokeFamily(familyId, NOW);
    }

    @Test
    void endSessionOfAnUnknownTokenIsANoOp() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThat(service.endSession("nope")).isEmpty();
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void revokeAllForRevokesEveryLiveTokenOfTheUser() {
        UUID userId = UUID.randomUUID();

        service.revokeAllFor(userId);

        verify(refreshTokenRepository, times(1)).revokeAllByUserId(userId, NOW);
    }

    private RefreshToken savedToken() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        return captor.getValue();
    }

    private static RefreshToken token(AppUser user, UUID familyId, Instant issuedAt, Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), user.getId(), "h", familyId, issuedAt, expiresAt, false, "ip",
                "ua");
    }

    private static AppUser activeUser() {
        UUID id = UUID.randomUUID();
        return new AppUser(id, id, "person@example.com", "hash", "Person", Role.MEMBER, UserStatus.ACTIVE);
    }
}
