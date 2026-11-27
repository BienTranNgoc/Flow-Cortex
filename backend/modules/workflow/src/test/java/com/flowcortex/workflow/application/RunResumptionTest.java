package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepOutputVersionRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link RunResumption} over mocked repositories: that a step of an iterate step whose child cannot run is
 * refused on every resume path, and that an iterate step is not re-armed while its items still run.
 */
class RunResumptionTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final String TTS_CHILD = "{\"child\":{\"type\":\"tts\"}}";

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final UUID ownerId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    private RunResumption resumption(boolean ttsAvailable) {
        StepExecutor iterate = mock(StepExecutor.class);
        when(iterate.type()).thenReturn("iterate");
        when(iterate.mode()).thenReturn(StepExecutor.Mode.FANOUT);
        when(iterate.available()).thenReturn(true);
        StepExecutor tts = mock(StepExecutor.class);
        when(tts.type()).thenReturn("tts");
        when(tts.mode()).thenReturn(StepExecutor.Mode.INLINE);
        when(tts.available()).thenReturn(ttsAvailable);
        when(tts.unavailableRefusal()).thenCallRealMethod();
        doAnswer(call -> {
            Consumer<TransactionStatus> body = call.getArgument(0);
            body.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        return new RunResumption(workflowRepository, workflowRunRepository, runStepRepository,
                mock(RunStepOutputVersionRepository.class), new StepExecutorRegistry(List.of(iterate, tts)),
                mediaJobs, transactionTemplate, mock(RunUpdates.class), new RunStartGuards(List.of()), events,
                RunViewFactsFactories.insightOff(), true);
    }

    /** A failed run whose one step is an iterate step over {@code tts} items, in {@code status}. */
    private RunStep givenFailedIterateRun(boolean stepFailed) {
        WorkflowRun run = new WorkflowRun(runId, workflowId, ownerId, "in", NOW);
        run.markError("item 0 failed", NOW);
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "iterate", null, TTS_CHILD, null);
        if (stepFailed) {
            step.markRunning(NOW);
            step.markError("in", "iterate_item_failed", "item 0 failed", NOW);
        }
        when(workflowRunRepository.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of(step));
        when(workflowRepository.findVisibleTo(workflowId, ownerId))
                .thenReturn(Optional.of(new Workflow(workflowId, ownerId, "Demo", false)));
        return step;
    }

    @Test
    void retryingAStepIsRefusedWhenAnIterateStepsChildTypeIsUnavailable() {
        RunStep step = givenFailedIterateRun(true);

        assertThatThrownBy(() -> resumption(false).retryStep(ownerId, runId, step.getId()))
                .isInstanceOf(DubDisabledException.class);

        verify(runStepRepository, never()).reArm(any(), any(), anyShort(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void executingAStepIsRefusedWhenAnIterateStepsChildTypeIsUnavailable() {
        RunStep step = givenFailedIterateRun(false);

        assertThatThrownBy(() -> resumption(false).executeStep(ownerId, runId, step.getId()))
                .isInstanceOf(DubDisabledException.class);

        verify(workflowRunRepository, never()).reopen(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void retryingARunIsRefusedWhenAnIterateStepsChildTypeIsUnavailable() {
        givenFailedIterateRun(true);

        assertThatThrownBy(() -> resumption(false).retryRun(ownerId, runId, null, true))
                .isInstanceOf(DubDisabledException.class);

        verify(workflowRunRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void retryingAnIterateStepIsRefusedWhileAnItemOfAnEarlierAttemptStillRuns() {
        RunStep step = givenFailedIterateRun(true);
        when(runStepRepository.countRunningChildrenOfAnyAttempt(step.getId())).thenReturn(1L);

        assertThatThrownBy(() -> resumption(true).retryStep(ownerId, runId, step.getId()))
                .isInstanceOf(ConflictException.class);

        verify(runStepRepository, never()).reArm(any(), any(), anyShort(), any());
        verify(workflowRunRepository, never()).reopen(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void resettingAnIterateStepIsRefusedWhileAnItemOfAnEarlierAttemptStillRuns() {
        RunStep step = givenFailedIterateRun(true);
        when(runStepRepository.countRunningChildrenOfAnyAttempt(step.getId())).thenReturn(2L);

        assertThatThrownBy(() -> resumption(true).resetStep(ownerId, runId, step.getId()))
                .isInstanceOf(ConflictException.class);

        verify(runStepRepository, never()).reArm(any(), any(), anyShort(), any());
    }

    @Test
    void retryingAnIterateStepRunsOnceNoItemIsRunning() {
        RunStep step = givenFailedIterateRun(true);
        when(runStepRepository.countRunningChildrenOfAnyAttempt(step.getId())).thenReturn(0L);
        when(runStepRepository.reArm(eq(step.getId()), eq(runId), eq(step.getAttempt()), any())).thenReturn(1);
        when(workflowRunRepository.reopen(runId, ownerId)).thenReturn(1);

        resumption(true).retryStep(ownerId, runId, step.getId());

        verify(runStepRepository).reArm(eq(step.getId()), eq(runId), eq(step.getAttempt()), any());
        verify(workflowRunRepository).reopen(runId, ownerId);
    }
}
