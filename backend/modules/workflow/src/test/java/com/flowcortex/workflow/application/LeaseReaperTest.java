package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaLimits;
import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Pageable;

/** {@link LeaseReaper}: which bound closed a stuck step, that only a won CAS counts, and the stalled-run sweep. */
@ExtendWith(OutputCaptureExtension.class)
class LeaseReaperTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final RunAdvancer advancer = mock(RunAdvancer.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LeaseReaper reaper = new LeaseReaper(runStepRepository, workflowRunRepository,
            new RunStepTransactions(runStepRepository, workflowRunRepository, mock(CreditAccounts.class)),
            new StepExecutorRegistry(mediaJobs, Clock.systemUTC()), advancer, meters,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID runId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(runId, UUID.randomUUID(), UUID.randomUUID(), "in", NOW);

    private RunStep claimed(Instant queueDeadline) {
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "probe");
        step.markRunning(NOW.minusSeconds(3600), queueDeadline);
        return step;
    }

    private void givenReapable(List<RunStep> steps, int casResult) {
        when(runStepRepository.findReapable(eq(NOW), any(Pageable.class))).thenReturn(steps);
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(casResult);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(mediaJobs.limitsFor("probe"))
                .thenReturn(new MediaLimits(Duration.ofMinutes(30), Duration.ofMinutes(5), 1000L));
    }

    private double reaped(String code) {
        return reaped(code, "remote");
    }

    private double reaped(String code, String mode) {
        return meters.get("media.reaper.reaped").tags("code", code, "mode", mode).counter().count();
    }

    @Test
    void everyCodeIsCountedFromZeroForBothModesBeforeAnyStepIsReaped() {
        for (String code : List.of("queue_timeout", "lease_expired", "run_timeout")) {
            for (String mode : List.of("inline", "remote")) {
                assertThat(reaped(code, mode)).as(code + "/" + mode).isZero();
            }
        }
        assertThat(meters.get("media.reaper.reaped").counters()).hasSize(6);
    }

    @Test
    void anInlineStepIsCountedUnderTheInlineMode(CapturedOutput output) {
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "echo");
        step.markRunning(NOW.minusSeconds(3600), NOW.minusSeconds(1));
        givenReapable(List.of(step), 1);

        reaper.reap();

        assertThat(reaped("queue_timeout", "inline")).isEqualTo(1.0);
        assertThat(reaped("queue_timeout", "remote")).isZero();
        assertThat(output).contains("Step reaped: runId=" + runId + " runStepId=" + step.getId());
    }

    @Test
    void anUnpickedStepPastItsQueueDeadlineIsAQueueTimeout() {
        RunStep step = claimed(NOW.minusSeconds(1));
        givenReapable(List.of(step), 1);

        reaper.reap();

        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "queue_timeout",
                "not picked up within the queue timeout", NOW);
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        assertThat(reaped("queue_timeout")).isEqualTo(1.0);
    }

    @Test
    void aPickedUpStepPastItsLeaseIsLeaseExpired() {
        RunStep step = claimed(NOW.minusSeconds(1));
        step.recordPickup(NOW.minusSeconds(200), NOW.minusSeconds(1), NOW.plusSeconds(100), (short) 0);
        givenReapable(List.of(step), 1);

        reaper.reap();

        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "lease_expired",
                "no heartbeat from the executor within the lease", NOW);
        assertThat(reaped("lease_expired")).isEqualTo(1.0);
        assertThat(reaped("queue_timeout")).isZero();
    }

    @Test
    void aPickedUpStepPastItsRunDeadlineIsARunTimeoutNamingTheLimit() {
        RunStep step = claimed(NOW.plusSeconds(1000));
        step.recordPickup(NOW.minusSeconds(400), NOW.plusSeconds(60), NOW.minusSeconds(1), (short) 0);
        givenReapable(List.of(step), 1);

        reaper.reap();

        verify(runStepRepository).failIfRunning(step.getId(), runId, (short) 1, "run_timeout",
                "exceeded the maximum run time of 300 s", NOW);
        assertThat(reaped("run_timeout")).isEqualTo(1.0);
    }

    @Test
    void whenBothBoundsHavePassedTheEarlierOneNamesTheFailure() {
        RunStep step = claimed(NOW.plusSeconds(1000));
        step.recordPickup(NOW.minusSeconds(400), NOW.minusSeconds(100), NOW.minusSeconds(10), (short) 0);
        givenReapable(List.of(step), 1);

        reaper.reap();

        verify(runStepRepository).failIfRunning(eq(step.getId()), eq(runId), eq((short) 1), eq("lease_expired"),
                any(), eq(NOW));
    }

    @Test
    void aStepWhoseResultWonTheRaceIsNotCountedAndTheRunIsLeftAlone() {
        RunStep step = claimed(NOW.minusSeconds(1));
        givenReapable(List.of(step), 0);

        reaper.reap();

        assertThat(reaped("queue_timeout")).isZero();
        assertThat(run.getStatus().name()).isEqualTo("RUNNING");
        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void onePassIsBoundedToAPage() {
        givenReapable(List.of(), 1);

        reaper.reap();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(runStepRepository).findReapable(eq(NOW), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(LeaseReaper.BATCH_SIZE);
    }

    @Test
    void aStepThatFailsToCloseIsLoggedAndTheRestOfThePassContinues(CapturedOutput output) {
        RunStep broken = claimed(NOW.minusSeconds(1));
        RunStep healthy = claimed(NOW.minusSeconds(1));
        givenReapable(List.of(broken, healthy), 1);
        doThrow(new IllegalStateException("row locked")).when(runStepRepository)
                .failIfRunning(eq(broken.getId()), any(), anyShort(), any(), any(), any());

        reaper.reap();

        verify(runStepRepository).failIfRunning(eq(healthy.getId()), any(), anyShort(), any(), any(), any());
        assertThat(reaped("queue_timeout")).isEqualTo(1.0);
        assertThat(output).contains("ERROR")
                .contains("Could not close step: runId=" + runId + " runStepId=" + broken.getId())
                .contains("java.lang.IllegalStateException: row locked");
    }

    @Test
    void aRunningRunWithAPendingStepAndNothingInFlightIsAdvancedAgain() {
        givenReapable(List.of(), 1);
        UUID stalled = UUID.randomUUID();
        when(workflowRunRepository.findStalledRunIds(any(Pageable.class))).thenReturn(List.of(stalled));

        reaper.reap();

        verify(advancer).advance(stalled);
    }

    @Test
    void theSweepStampsTheRunsItTakesBeforeAdvancingThemSoOneThatNeverMovesGoesToTheBack() {
        givenReapable(List.of(), 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(workflowRunRepository.findStalledRunIds(any(Pageable.class))).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("claim failed")).when(advancer).advance(first);

        reaper.reap();

        InOrder order = inOrder(workflowRunRepository, advancer);
        order.verify(workflowRunRepository).markSwept(List.of(first, second), NOW);
        order.verify(advancer).advance(first);
        order.verify(advancer).advance(second);
    }

    @Test
    void aSweepThatFindsNoStalledRunWritesNothing() {
        givenReapable(List.of(), 1);
        when(workflowRunRepository.findStalledRunIds(any(Pageable.class))).thenReturn(List.of());

        reaper.reap();

        verify(workflowRunRepository, never()).markSwept(any(), any());
    }

    @Test
    void aFailingReAdvanceIsLoggedAndDoesNotStopTheSweep(CapturedOutput output) {
        givenReapable(List.of(), 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(workflowRunRepository.findStalledRunIds(any(Pageable.class))).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("claim failed")).when(advancer).advance(first);

        reaper.reap();

        verify(advancer).advance(second);
        assertThat(output).contains("ERROR").contains(first.toString());
    }
}
