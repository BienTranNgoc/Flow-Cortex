package com.flowcortex.user.pat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.security.PersonalAccessTokenAuthenticator;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.api.StepUpRequiredException;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.pat.PersonalAccessTokenService.CreatedToken;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

class PersonalAccessTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PROOF = "proof-value";
    private static final String IP = "203.0.113.7";
    private static final String AGENT = "test-agent";

    private final PersonalAccessTokenRepository tokens = mock(PersonalAccessTokenRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final StepUpProofs stepUp = mock(StepUpProofs.class);
    private final AuditEventWriter audit = mock(AuditEventWriter.class);
    private final UUID userId = UUID.randomUUID();

    private PersonalAccessTokenService service(boolean enabled) {
        return new PersonalAccessTokenService(tokens, users, stepUp, audit,
                new PersonalAccessTokenProperties(enabled, 10, Duration.ofDays(90)), CLOCK);
    }

    private AppUser owner(Role role, UserStatus status) {
        return new AppUser(userId, userId, "admin@example.com", "hash", "Admin", role, status);
    }

    private void ownerIs(AppUser user) {
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    private PersonalAccessToken live(String raw) {
        return new PersonalAccessToken(UUID.randomUUID(), userId, "CI", OpaqueTokens.hash(raw), NOW.minusSeconds(60),
                NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void createReturnsATokenStoresOnlyItsHashAndDefaultsTheLifetimeTo90Days() {
        ownerIs(owner(Role.ADMIN, UserStatus.ACTIVE));
        when(tokens.countLive(userId, NOW)).thenReturn(0L);

        CreatedToken created = service(true).create(userId, "CI runner", null, PROOF, IP, AGENT);

        assertThat(created.rawToken()).matches("^fc_pat_[A-Za-z0-9_-]{43}$");
        ArgumentCaptor<PersonalAccessToken> saved = ArgumentCaptor.forClass(PersonalAccessToken.class);
        verify(tokens).save(saved.capture());
        assertThat(saved.getValue().getTokenHash()).isEqualTo(OpaqueTokens.hash(created.rawToken()));
        assertThat(saved.getValue().getTokenHash()).doesNotContain("fc_pat_");
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
        assertThat(saved.getValue().getName()).isEqualTo("CI runner");
        verify(stepUp).consume(userId, StepUpPurpose.PAT_CREATE, PROOF);
    }

    @Test
    void createHonoursAGivenLifetimeAndAuditsWithoutTheToken() {
        ownerIs(owner(Role.ADMIN, UserStatus.ACTIVE));

        CreatedToken created = service(true).create(userId, "CI runner", 30, PROOF, IP, AGENT);

        assertThat(created.token().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.captor();
        verify(audit).write(eq(AuthEventType.PAT_CREATED), eq(userId), eq(IP), eq(AGENT), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("userId", userId.toString())
                .containsEntry("tokenId", created.token().getId().toString()).containsEntry("name", "CI runner");
        assertThat(metadata.getValue().toString()).doesNotContain("fc_pat_").doesNotContain(created.rawToken());
    }

    @Test
    void anIneligibleCallerIsRefusedBeforeAnyProofIsSpent() {
        ownerIs(owner(Role.MEMBER, UserStatus.ACTIVE));

        assertThatThrownBy(() -> service(true).create(userId, "CI", null, PROOF, IP, AGENT))
                .isInstanceOf(PatNotEligibleException.class);

        verifyNoInteractions(stepUp);
        verify(tokens, never()).save(any());
    }

    @Test
    void aLockedOwnerCannotCreateOrList() {
        AppUser locked = owner(Role.ADMIN, UserStatus.ACTIVE);
        locked.recordFailedLogin(1, Duration.ofMinutes(15), NOW);
        ownerIs(locked);

        assertThatThrownBy(() -> service(true).create(userId, "CI", null, PROOF, IP, AGENT))
                .isInstanceOf(PatNotEligibleException.class);
        assertThatThrownBy(() -> service(true).list(userId)).isInstanceOf(PatNotEligibleException.class);
        verifyNoInteractions(stepUp);
    }

    @Test
    void theEleventhTokenIsRefusedWithoutSpendingTheProof() {
        ownerIs(owner(Role.ADMIN, UserStatus.ACTIVE));
        when(tokens.countLive(userId, NOW)).thenReturn(10L);

        assertThatThrownBy(() -> service(true).create(userId, "CI", null, PROOF, IP, AGENT))
                .isInstanceOf(PatLimitReachedException.class);

        verify(stepUp, never()).consume(any(), any(), anyString());
        verify(tokens, never()).save(any());
    }

    @Test
    void aRefusedProofSavesNothing() {
        ownerIs(owner(Role.ADMIN, UserStatus.ACTIVE));
        doThrow(new StepUpRequiredException()).when(stepUp).consume(userId, StepUpPurpose.PAT_CREATE, PROOF);

        assertThatThrownBy(() -> service(true).create(userId, "CI", null, PROOF, IP, AGENT))
                .isInstanceOf(StepUpRequiredException.class);

        verify(tokens, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void listReturnsTheCallersUnrevokedTokensAndFlagsExpiredOnes() {
        ownerIs(owner(Role.ADMIN, UserStatus.ACTIVE));
        PersonalAccessToken fresh = live("a");
        PersonalAccessToken old = new PersonalAccessToken(UUID.randomUUID(), userId, "old", "h", NOW.minusSeconds(900),
                NOW.minusSeconds(1));
        when(tokens.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId)).thenReturn(List.of(fresh, old));

        List<PersonalAccessTokenService.TokenSummary> list = service(true).list(userId);

        assertThat(list).extracting(PersonalAccessTokenService.TokenSummary::expired).containsExactly(false, true);
    }

    @Test
    void authenticateTurnsALiveTokenIntoARolelessPrincipalAndTouchesLastUsedOnce() {
        String raw = "fc_pat_" + OpaqueTokens.generateRawToken();
        PersonalAccessToken token = live(raw);
        when(tokens.findByTokenHash(OpaqueTokens.hash(raw))).thenReturn(Optional.of(token));
        when(users.findById(userId)).thenReturn(Optional.of(owner(Role.ADMIN, UserStatus.ACTIVE)));

        Jwt jwt = service(true).authenticate(raw).orElseThrow();

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.<String>getClaim(PersonalAccessTokenAuthenticator.AUTH_CLAIM))
                .isEqualTo(PersonalAccessTokenAuthenticator.AUTH_PAT);
        assertThat(jwt.<String>getClaim("pat_id")).isEqualTo(token.getId().toString());
        assertThat(jwt.getClaims()).doesNotContainKey("role");
        assertThat(jwt.getTokenValue()).doesNotContain("fc_pat_");
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        verify(tokens, times(1)).touchLastUsed(token.getId(), NOW);
    }

    @Test
    void theAuthenticationExpiryNeverOutlivesTheToken() {
        String raw = "fc_pat_" + OpaqueTokens.generateRawToken();
        PersonalAccessToken shortLived = new PersonalAccessToken(UUID.randomUUID(), userId, "CI",
                OpaqueTokens.hash(raw), NOW.minusSeconds(60), NOW.plusSeconds(20));
        when(tokens.findByTokenHash(OpaqueTokens.hash(raw))).thenReturn(Optional.of(shortLived));
        when(users.findById(userId)).thenReturn(Optional.of(owner(Role.ADMIN, UserStatus.ACTIVE)));

        assertThat(service(true).authenticate(raw).orElseThrow().getExpiresAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void aLockedOwnersTokenStillAuthenticates() {
        String raw = "fc_pat_" + OpaqueTokens.generateRawToken();
        when(tokens.findByTokenHash(OpaqueTokens.hash(raw))).thenReturn(Optional.of(live(raw)));
        AppUser locked = owner(Role.ADMIN, UserStatus.ACTIVE);
        locked.recordFailedLogin(1, Duration.ofMinutes(15), NOW);
        when(users.findById(userId)).thenReturn(Optional.of(locked));

        assertThat(service(true).authenticate(raw)).isPresent();
    }

    @Test
    void everyRefusalAuthenticatesNobodyAndTouchesNothing() {
        String raw = "fc_pat_" + OpaqueTokens.generateRawToken();
        String hash = OpaqueTokens.hash(raw);
        AppUser admin = owner(Role.ADMIN, UserStatus.ACTIVE);
        when(users.findById(userId)).thenReturn(Optional.of(admin));

        when(tokens.findByTokenHash(hash)).thenReturn(Optional.empty());
        assertThat(service(true).authenticate(raw)).as("unknown").isEmpty();

        PersonalAccessToken revoked = live(raw);
        ReflectionTestUtils.setField(revoked, "revokedAt", NOW.minusSeconds(5));
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(revoked));
        assertThat(service(true).authenticate(raw)).as("revoked").isEmpty();

        PersonalAccessToken expired = new PersonalAccessToken(UUID.randomUUID(), userId, "CI", hash,
                NOW.minusSeconds(60), NOW);
        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(expired));
        assertThat(service(true).authenticate(raw)).as("expiresAt == now").isEmpty();

        when(tokens.findByTokenHash(hash)).thenReturn(Optional.of(live(raw)));
        assertThat(service(false).authenticate(raw)).as("switch off").isEmpty();
        assertThat(service(true).authenticate("not-a-pat")).as("no prefix").isEmpty();

        when(users.findById(userId)).thenReturn(Optional.of(owner(Role.ADMIN, UserStatus.SUSPENDED)));
        assertThat(service(true).authenticate(raw)).as("owner suspended").isEmpty();
        when(users.findById(userId)).thenReturn(Optional.of(owner(Role.MEMBER, UserStatus.ACTIVE)));
        assertThat(service(true).authenticate(raw)).as("owner demoted").isEmpty();
        when(users.findById(userId)).thenReturn(Optional.empty());
        assertThat(service(true).authenticate(raw)).as("owner gone").isEmpty();

        verify(tokens, never()).touchLastUsed(any(), any());
    }

    @Test
    void revokeAuditsTheNamedTokenAndAnUnknownOrForeignOrAlreadyRevokedOneIsNotFound() {
        PersonalAccessToken token = live("x");
        when(tokens.findByIdAndUserIdAndRevokedAtIsNull(token.getId(), userId)).thenReturn(Optional.of(token));
        when(tokens.revoke(token.getId(), userId, NOW)).thenReturn(1);

        service(true).revoke(userId, token.getId(), IP, AGENT);

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.captor();
        verify(audit).write(eq(AuthEventType.PAT_REVOKED), eq(userId), eq(IP), eq(AGENT), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("tokenId", token.getId().toString()).containsEntry("name", "CI");

        UUID other = UUID.randomUUID();
        when(tokens.findByIdAndUserIdAndRevokedAtIsNull(other, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(true).revoke(userId, other, IP, AGENT)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aRevokeThatLosesTheRaceIsNotFoundAndWritesNoAudit() {
        PersonalAccessToken token = live("x");
        when(tokens.findByIdAndUserIdAndRevokedAtIsNull(token.getId(), userId)).thenReturn(Optional.of(token));
        when(tokens.revoke(token.getId(), userId, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service(true).revoke(userId, token.getId(), IP, AGENT))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(audit);
    }
}
