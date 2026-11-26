package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.settings.api.SettingRead;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

class RunCancellationTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunStepRepository steps = mock(RunStepRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final RunUpdates runUpdates = mock(RunUpdates.class);
    private final ChildRuns childRuns = mock(ChildRuns.class);
    private final RunStepTransactions transactions = mock(RunStepTransactions.class);
    private final RunCancellation cancellation = new RunCancellation(runs, steps,
            new StepExecutorRegistry(mediaJobs, Clock.systemUTC()), mediaJobs, Clock.fixed(NOW, ZoneOffset.UTC),
            runUpdates, childRuns, transactions, switchReading(true));
    private final RunCancellation switchedOff = new RunCancellation(runs, steps,
            new StepExecutorRegistry(mediaJobs, Clock.systemUTC()), mediaJobs, Clock.fixed(NOW, ZoneOffset.UTC),
            runUpdates, childRuns, transactions, switchReading(false));
    private final UUID adminId = UUID.randomUUID();

    private final UUID ownerId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(runId, UUID.randomUUID(), ownerId, "in", NOW);

    @Test
    void aStopThatTakesEffectSignalsTheRun() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(1);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(runUpdates).touched(runId);
    }

    @Test
    void aMemberStopIsRefusedWith503WhileTheSwitchIsOffAndChangesNothing() {
        assertThatThrownBy(() -> switchedOff.cancelRun(ownerId, runId))
                .isInstanceOfSatisfying(LocalizedStatusException.class,
                        refusal -> assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(runs, never()).requestCancel(any(), any());
        verify(runUpdates, never()).touched(any());
    }

    @Test
    void anAdminStopOfAnotherMembersRunTakesEffectWithTheMemberSwitchOff() {
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runs.requestCancel(runId, ownerId)).thenReturn(1);
        when(steps.skipPending(runId)).thenReturn(2);

        switchedOff.cancelRunAsAdmin(adminId, runId);

        verify(steps).skipPending(runId);
        verify(runUpdates).touched(runId);
    }

    @Test
    void anAdminStopOfAFinishedRunIsAConflict() {
        run.markCompleted(NOW);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runs.requestCancel(runId, ownerId)).thenReturn(0);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> cancellation.cancelRunAsAdmin(adminId, runId)).isInstanceOf(ConflictException.class);
        verify(runUpdates, never()).touched(any());
    }

    @Test
    void anAdminStopOfAnUnknownRunIsNotFound() {
        when(runs.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cancellation.cancelRunAsAdmin(adminId, runId)).isInstanceOf(NotFoundException.class);
        verify(runs, never()).requestCancel(any(), any());
    }

    @Test
    void anAdminStopOnARunAlreadyStoppingLeavesItUnchanged() {
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runs.requestCancel(runId, ownerId)).thenReturn(0);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));

        cancellation.cancelRunAsAdmin(adminId, runId);

        verify(runUpdates, never()).touched(any());
        verify(steps, never()).skipPending(any());
    }

    @Test
    void anAdminStopUsesTheOwnerScopedCoreWithTheRunsOwner() {
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runs.requestCancel(runId, ownerId)).thenReturn(1);

        cancellation.cancelRunAsAdmin(adminId, runId);

        verify(runs).requestCancel(runId, ownerId);
        verify(runs, never()).requestCancel(runId, adminId);
    }

    @Test
    void cancellingARunStopsItsChildren() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(1);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(childRuns).stopChildrenOf(runId);
    }

    @Test
    void aRunSettledAtOnceSignalsIt() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(1);
        when(runs.settleCancelled(runId, NOW)).thenReturn(1);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(childRuns).finished(runId);
    }

    @Test
    void aRunLeftCancellingSignalsNothingFinished() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(1);
        when(runs.settleCancelled(runId, NOW)).thenReturn(0);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(childRuns, never()).finished(any());
    }

    @Test
    void aStopOnARunAlreadyStoppingStopsNoChildren() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(0);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(childRuns, never()).stopChildrenOf(any());
    }

    @Test
    void aStopOnARunAlreadyStoppingSignalsNothing() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(0);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(runUpdates, never()).touched(any());
    }

    @Test
    void stopWithOnlyPendingChildrenFailsTheIterateAndSettlesAtOnce() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(1);
        when(steps.skipPending(runId)).thenReturn(4);
        when(transactions.failIdleIterateParents(runId, NOW)).thenReturn(true);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        InOrder order = inOrder(steps, transactions, childRuns);
        order.verify(steps).skipPending(runId);
        order.verify(transactions).failIdleIterateParents(runId, NOW);
        order.verify(childRuns).stopChildrenOf(runId);
        verify(runs, never()).settleCancelled(any(), any());
        verify(childRuns, never()).finished(any());
    }

    @Test
    void stopWithARunningChildLeavesTheIterateToTheChildsEnd() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(1);
        when(transactions.failIdleIterateParents(runId, NOW)).thenReturn(false);
        when(runs.settleCancelled(runId, NOW)).thenReturn(0);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(runs).settleCancelled(runId, NOW);
        verify(childRuns, never()).finished(any());
    }

    private static Settings switchReading(boolean on) {
        Settings settings = mock(Settings.class);
        when(settings.get(WorkflowSettingKeys.RUN_CANCELLATION_ENABLED))
                .thenReturn(new SettingRead<>(on, on, SettingSource.STORED));
        return settings;
    }
}
