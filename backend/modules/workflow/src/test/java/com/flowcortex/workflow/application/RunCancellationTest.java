package com.flowcortex.workflow.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
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
    void aStopOnARunAlreadyStoppingSignalsNothing() {
        when(runs.requestCancel(runId, ownerId)).thenReturn(0);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(steps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of());

        cancellation.cancelRun(ownerId, runId);

        verify(runUpdates, never()).touched(any());
    }
}
