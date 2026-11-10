package com.flowcortex.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.onboarding.domain.ProgressPatch;
import com.flowcortex.onboarding.infrastructure.OnboardingRecord;
import com.flowcortex.onboarding.infrastructure.OnboardingRepository;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.RunHistory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OnboardingServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    private final UUID userId = UUID.randomUUID();
    private OnboardingRepository repository;
    private RunHistory runHistory;

    @BeforeEach
    void setUp() {
        repository = mock(OnboardingRepository.class);
        runHistory = mock(RunHistory.class);
    }

    private OnboardingService service(boolean switchOn) {
        return new OnboardingService(repository, runHistory, new OnboardingSwitch(switchOn),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ObjectNode progress(String json) {
        return (ObjectNode) JSON.readTree(json);
    }

    @Test
    void aBodyTheParserRefusesIsA422BeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service(true).submitQuiz(userId, JSON.readTree("{\"aiLevel\":\"expert\"}")))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("ONBOARDING_INVALID");
                    assertThat(e.getErrors()).containsOnlyKeys("aiLevel");
                });
        verifyNoInteractions(repository);
    }

    @Test
    void aClosedFlowRefusesANonFinishingProgressWriteWithConflict() {
        when(repository.writeProgress(eq(userId), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(true).saveProgress(userId, JSON.readTree("{\"step\":\"quiz\"}")))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).markCompleted(any(), any());
    }

    @Test
    void aFinishThatLostTheRaceReadsTheStoredProgressAndStampsCompletion() {
        ObjectNode stored = progress("{\"v\":2,\"finishedAt\":\"2026-11-10T08:00:00Z\"}");
        Instant completedAt = Instant.parse("2026-11-10T08:00:00Z");
        when(repository.writeProgress(eq(userId), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(Optional.empty());
        when(repository.readProgress(userId)).thenReturn(stored);
        when(repository.markCompleted(userId, NOW)).thenReturn(completedAt);

        OnboardingService.ProgressSaved saved = service(true).saveProgress(userId, JSON.readTree("{\"finished\":true}"));

        assertThat(saved.progress()).isEqualTo(stored);
        assertThat(saved.completedAt()).isEqualTo(completedAt);
    }

    @Test
    void aFinishOnAClosedFlowWithNoFinishedAtIsAConflict() {
        when(repository.writeProgress(eq(userId), any(), anyBoolean(), anyBoolean(), any()))
                .thenReturn(Optional.empty());
        when(repository.readProgress(userId)).thenReturn(progress("{}"));

        assertThatThrownBy(() -> service(true).saveProgress(userId, JSON.readTree("{\"finished\":true}")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void withTheSwitchOffEvenAFinishedJourneyRefusesAFinish() {
        when(repository.writeProgress(eq(userId), any(), eq(false), anyBoolean(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(false).saveProgress(userId, JSON.readTree("{\"finished\":true}")))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).readProgress(any());
    }

    @Test
    void anOpenFlowPassesTheFirstRunFactAndTheSwitchToTheGuardedWrite() {
        ObjectNode written = progress("{\"v\":2,\"step\":\"welcome\"}");
        when(runHistory.hasCompletedRun(userId)).thenReturn(true);
        when(repository.writeProgress(eq(userId), any(ProgressPatch.class), eq(true), eq(true), eq(NOW)))
                .thenReturn(Optional.of(written));

        OnboardingService.ProgressSaved saved = service(true).saveProgress(userId, JSON.readTree("{\"step\":\"welcome\"}"));

        assertThat(saved.progress()).isEqualTo(written);
        assertThat(saved.completedAt()).isNull();
        verify(repository).ensureRow(userId, NOW);
    }

    @Test
    void aMemberWithNoRowReadsAsOpenWithTheFirstEntry() {
        when(repository.find(userId)).thenReturn(Optional.empty());

        OnboardingService.Status status = service(true).status(userId);

        assertThat(status.completed()).isFalse();
        assertThat(status.firstRunDone()).isFalse();
        assertThat(status.open()).isTrue();
        assertThat(status.entry()).isEqualTo("first");
        assertThat(status.record()).isEqualTo(OnboardingRecord.empty());
    }

    @Test
    void withTheSwitchOffTheStatusIsNeverOpen() {
        when(repository.find(userId)).thenReturn(Optional.empty());

        OnboardingService.Status status = service(false).status(userId);

        assertThat(status.open()).isFalse();
        assertThat(status.entry()).isNull();
    }

    @Test
    void aBuilderCompletionWithAnUnknownReasonIsRefusedBeforeStamping() {
        when(repository.findForUpdate(userId)).thenReturn(Optional.of(OnboardingRecord.empty()));

        assertThatThrownBy(() -> service(true).completeBuilder(userId, JSON.readTree("{\"reason\":\"later\"}")))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("reason"));
        verify(repository, never()).completeBuilder(any(), any(), any());
    }

    private static OnboardingRecord completedBuilder(ObjectNode state, Instant completedAt) {
        OnboardingRecord empty = OnboardingRecord.empty();
        return new OnboardingRecord(empty.answers(), null, empty.progress(), state, completedAt);
    }

    @Test
    void aRepeatBuilderCompletionOverANearCapStateReturnsTheStoredStateUnchanged() {
        ObjectNode nearCap = JSON.createObjectNode().put("pad", "x".repeat(8_160));
        Instant first = Instant.parse("2026-11-10T08:00:00Z");
        when(repository.findForUpdate(userId)).thenReturn(Optional.of(completedBuilder(nearCap, first)));

        OnboardingService.Builder builder = service(true).completeBuilder(userId,
                JSON.readTree("{\"reason\":\"finished\"}"));

        assertThat(builder.state()).isEqualTo(nearCap);
        assertThat(builder.completedAt()).isEqualTo(first);
        verify(repository, never()).completeBuilder(any(), any(), any());
    }

    @Test
    void aRepeatBuilderCompletionStillRefusesAnUnknownReason() {
        when(repository.findForUpdate(userId)).thenReturn(Optional.of(
                completedBuilder(JSON.createObjectNode(), Instant.parse("2026-11-10T08:00:00Z"))));

        assertThatThrownBy(() -> service(true).completeBuilder(userId, JSON.readTree("{\"reason\":\"later\"}")))
                .isInstanceOf(UnprocessableEntityException.class);
    }
}
