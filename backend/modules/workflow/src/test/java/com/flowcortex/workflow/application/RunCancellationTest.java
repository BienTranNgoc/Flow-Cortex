package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.ConflictException;
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

class RunCancellationTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunStepRepository steps = mock(RunStepRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final RunUpdates runUpdates = mock(RunUpdates.class);
    private final RunCancellation cancellation = new RunCancellation(runs, steps,
            new StepExecutorRegistry(mediaJobs, Clock.systemUTC()), mediaJobs, Clock.fixed(NOW, ZoneOffset.UTC),
            runUpdates, true);
    private final RunCancellation switchedOff = new RunCancellation(runs, steps,
            new StepExecutorRegistry(mediaJobs, Clock.systemUTC()), mediaJobs, Clock.fixed(NOW, ZoneOffset.UTC),
            runUpdates, false);
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
    void aStopOnARunAlreadyStoppingSignalsNothing() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(0);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(runUpdates, never()).touched(any());
    }
}
