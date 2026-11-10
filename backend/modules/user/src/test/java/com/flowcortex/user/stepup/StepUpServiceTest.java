package com.flowcortex.user.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.ratelimit.InMemoryRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.api.StepUpRequiredException;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.stepup.StepUpService.MintedProof;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class StepUpServiceTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String PASSWORD = "correct horse battery staple";
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String IP = "203.0.113.7";
    private static final String AGENT = "test-agent";

    private final StepUpProofRepository proofs = mock(StepUpProofRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = spy(ENCODER);
    private final AuditEventWriter audit = mock(AuditEventWriter.class);
    private final UUID userId = UUID.randomUUID();

    private StepUpService service(int capacity) {
        RateLimitDefinition definition = new RateLimitDefinition(StepUpRateLimits.STEP_UP_USER, capacity,
                Duration.ofMinutes(1));
        RateLimiters limiters = new RateLimiters(java.util.List.of(definition),
                new InMemoryRateLimitStore(CLOCK), new RateLimitTelemetry(new SimpleMeterRegistry(), CLOCK));
        return new StepUpService(proofs, users, encoder, audit, limiters, CLOCK);
    }

    private AppUser user(boolean hasPassword) {
        return new AppUser(userId, userId, "person@example.com", hasPassword ? ENCODER.encode(PASSWORD) : "", "Person",
                Role.ADMIN, UserStatus.ACTIVE, hasPassword);
    }

    @Test
    void correctPasswordMintsAProofStoredOnlyAsItsHash() {
        when(users.findById(userId)).thenReturn(Optional.of(user(true)));

        MintedProof minted = service(5).mint(userId, PASSWORD, StepUpPurpose.PAT_CREATE, IP, AGENT);

        ArgumentCaptor<StepUpProof> saved = ArgumentCaptor.forClass(StepUpProof.class);
        verify(proofs).save(saved.capture());
        assertThat(minted.proof()).isNotBlank();
        assertThat(saved.getValue().getTokenHash()).isEqualTo(OpaqueTokens.hash(minted.proof()))
                .isNotEqualTo(minted.proof());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getPurpose()).isEqualTo("PAT_CREATE");
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(minted.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        ArgumentCaptor<Map<String, Object>> metadata = metadataCaptor();
        verify(audit).write(eq(AuthEventType.STEP_UP_SUCCEEDED), eq(userId), eq(IP), eq(AGENT), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("userId", userId.toString()).containsEntry("purpose",
                "PAT_CREATE");
        assertThat(metadata.getValue().toString()).doesNotContain(minted.proof());
    }

    @Test
    void wrongPasswordIsRefusedWith422AndMintsNothing() {
        AppUser account = user(true);
        when(users.findById(userId)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service(5).mint(userId, "wrong-password", StepUpPurpose.PAT_CREATE, IP, AGENT))
                .isInstanceOfSatisfying(StepUpInvalidPasswordException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(ex.getBody().getProperties()).containsEntry("code", "STEP_UP_INVALID_PASSWORD");
                });

        verify(proofs, never()).save(any());
        ArgumentCaptor<Map<String, Object>> metadata = metadataCaptor();
        verify(audit).write(eq(AuthEventType.STEP_UP_FAILED_INVALID_PASSWORD), eq(userId), eq(IP), eq(AGENT),
                metadata.capture());
        assertThat(metadata.getValue()).containsEntry("purpose", "PAT_CREATE");
        assertThat(metadata.getValue().toString()).doesNotContain("wrong-password");
        assertThat(account.getFailedLoginAttempts()).isZero();
        verify(users, never()).save(any());
    }

    @Test
    void accountWithoutAPasswordIsRefusedWith409BeforeAnyPasswordCompare() {
        when(users.findById(userId)).thenReturn(Optional.of(user(false)));

        assertThatThrownBy(() -> service(5).mint(userId, "anything", StepUpPurpose.PAT_CREATE, IP, AGENT))
                .isInstanceOfSatisfying(StepUpPasswordRequiredException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getBody().getProperties()).containsEntry("code", "STEP_UP_PASSWORD_REQUIRED");
                });

        verifyNoInteractions(encoder);
        verify(proofs, never()).save(any());
        verify(audit).write(eq(AuthEventType.STEP_UP_FAILED_PASSWORD_REQUIRED), eq(userId), eq(IP), eq(AGENT),
                any());
    }

    @Test
    void anAttemptOverTheUserLimitIsRefusedWith429BeforeThePasswordIsCompared() {
        when(users.findById(userId)).thenReturn(Optional.of(user(true)));
        StepUpService service = service(1);
        service.mint(userId, PASSWORD, StepUpPurpose.PAT_CREATE, IP, AGENT);

        assertThatThrownBy(() -> service.mint(userId, PASSWORD, StepUpPurpose.PAT_CREATE, IP, AGENT))
                .isInstanceOfSatisfying(StepUpRateLimitedException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(ex.getRetryAfterSeconds()).isPositive();
                    assertThat(ex.getHeaders().getFirst("Retry-After")).isEqualTo(
                            Long.toString(ex.getRetryAfterSeconds()));
                });

        verify(encoder).matches(any(), any());
        verify(proofs).save(any());
        verify(audit).write(eq(AuthEventType.STEP_UP_RATE_LIMITED), eq(userId), eq(IP), eq(AGENT), any());
    }

    @Test
    void consumeAcceptsAMatchingProof() {
        when(proofs.consume(eq(OpaqueTokens.hash("raw")), eq(NOW))).thenReturn(
                Optional.of(new ConsumedProof(userId, "PAT_CREATE", NOW.plusSeconds(10))));

        service(5).consume(userId, StepUpPurpose.PAT_CREATE, "raw");
    }

    @Test
    void consumeRefusesWhenNothingWasSpent() {
        when(proofs.consume(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(5).consume(userId, StepUpPurpose.PAT_CREATE, "raw"))
                .isInstanceOfSatisfying(StepUpRequiredException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getBody().getProperties()).containsEntry("code", "STEP_UP_REQUIRED");
                });
    }

    @Test
    void consumeRefusesABlankOrMissingProofWithoutTouchingTheStore() {
        StepUpService service = service(5);

        assertThatThrownBy(() -> service.consume(userId, StepUpPurpose.PAT_CREATE, null))
                .isInstanceOf(StepUpRequiredException.class);
        assertThatThrownBy(() -> service.consume(userId, StepUpPurpose.PAT_CREATE, " "))
                .isInstanceOf(StepUpRequiredException.class);
        verifyNoInteractions(proofs);
    }

    @Test
    void consumeRefusesAnotherUsersProof() {
        when(proofs.consume(any(), any())).thenReturn(
                Optional.of(new ConsumedProof(UUID.randomUUID(), "PAT_CREATE", NOW.plusSeconds(10))));

        assertThatThrownBy(() -> service(5).consume(userId, StepUpPurpose.PAT_CREATE, "raw"))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> metadataCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
