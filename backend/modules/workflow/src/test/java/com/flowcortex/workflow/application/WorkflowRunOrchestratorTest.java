package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkflowRunOrchestrator}'s dispatch loop, with every collaborator
 * mocked: sequential claim, halt-on-error, and the "another execution
 * already handling this" skip. End-to-end async execution against a real
 * database is {@code WorkflowRunEngineIT} (:boot:app) — this covers the
 * loop's own branching logic in isolation.
 */
class WorkflowRunOrchestratorTest {

    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final WorkflowStepRepository workflowStepRepository = mock(WorkflowStepRepository.class);
    private final RunStepTransactions transactions = mock(RunStepTransactions.class);
    private final InputResolver inputResolver = mock(InputResolver.class);
    private final StepDispatcher stepDispatcher = mock(StepDispatcher.class);

    private final WorkflowRunOrchestrator orchestrator = new WorkflowRunOrchestrator(workflowRunRepository,
            runStepRepository, workflowStepRepository, transactions, inputResolver, stepDispatcher);

    private WorkflowRun someRun(UUID runId, UUID workflowId) {
        return new WorkflowRun(runId, workflowId, UUID.randomUUID(), "hello", Instant.now());
    }

    @Test
    void missingRunIsHandledWithoutThrowing() {
        UUID runId = UUID.randomUUID();
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatCode(() -> orchestrator.on(new WorkflowRunStartedEvent(runId))).doesNotThrowAnyException();

        verify(transactions, never()).claim(any(), any());
    }

    @Test
    void twoStepsBothClaimAndCompleteInOrder() {
        UUID runId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowRun run = someRun(runId, workflowId);
        RunStep step1 = new RunStep(UUID.randomUUID(), runId, (short) 1, "echo");
        RunStep step2 = new RunStep(UUID.randomUUID(), runId, (short) 2, "echo");
        WorkflowStep def1 = new WorkflowStep(UUID.randomUUID(), workflowId, (short) 1, "first", "echo", null);
        WorkflowStep def2 = new WorkflowStep(UUID.randomUUID(), workflowId, (short) 2, "second", "echo", null);

        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of(step1, step2));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)).thenReturn(List.of(def1, def2));
        when(transactions.claim(any(), any())).thenReturn(true);
        when(inputResolver.resolve(eq(1), any(), eq("hello"), any())).thenReturn("hello");
        when(inputResolver.resolve(eq(2), any(), anyString(), any())).thenReturn("hello-out");
        when(stepDispatcher.execute("echo", "hello")).thenReturn("hello-out");
        when(stepDispatcher.execute("echo", "hello-out")).thenReturn("hello-out-2");

        orchestrator.on(new WorkflowRunStartedEvent(runId));

        verify(transactions, times(2)).claim(any(), any());
        verify(transactions).complete(eq(runId), eq(step1.getId()), eq("hello"), eq("hello-out"), any());
        verify(transactions).complete(eq(runId), eq(step2.getId()), eq("hello-out"), eq("hello-out-2"), any());
        verify(transactions, never()).fail(any(), any(), any(), any(), any());
    }

    @Test
    void aFailingStepStopsTheLoopBeforeTheNextClaim() {
        UUID runId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowRun run = someRun(runId, workflowId);
        RunStep step1 = new RunStep(UUID.randomUUID(), runId, (short) 1, "fail");
        RunStep step2 = new RunStep(UUID.randomUUID(), runId, (short) 2, "echo");
        WorkflowStep def1 = new WorkflowStep(UUID.randomUUID(), workflowId, (short) 1, "first", "fail", null);
        WorkflowStep def2 = new WorkflowStep(UUID.randomUUID(), workflowId, (short) 2, "second", "echo", null);

        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of(step1, step2));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)).thenReturn(List.of(def1, def2));
        when(transactions.claim(any(), any())).thenReturn(true);
        when(inputResolver.resolve(eq(1), any(), eq("hello"), any())).thenReturn("hello");
        when(stepDispatcher.execute("fail", "hello")).thenThrow(new IllegalStateException("simulated failure"));

        orchestrator.on(new WorkflowRunStartedEvent(runId));

        verify(transactions, times(1)).claim(any(), any());
        verify(transactions).fail(eq(runId), eq(step1.getId()), eq("hello"), eq("simulated failure"), any());
        verify(transactions, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void aLostClaimStopsTheLoopWithoutExecutingTheStep() {
        UUID runId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowRun run = someRun(runId, workflowId);
        RunStep step1 = new RunStep(UUID.randomUUID(), runId, (short) 1, "echo");

        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of(step1));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)).thenReturn(List.of());
        when(transactions.claim(any(), any())).thenReturn(false);

        orchestrator.on(new WorkflowRunStartedEvent(runId));

        verify(stepDispatcher, never()).execute(any(), any());
        verify(transactions, never()).complete(any(), any(), any(), any(), any());
        verify(transactions, never()).fail(any(), any(), any(), any(), any());
    }

    @Test
    void aStepAlreadyCompletedByAnotherInvocationIsSkippedAndTheNextIsClaimed() {
        UUID runId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowRun run = someRun(runId, workflowId);
        RunStep step1 = new RunStep(UUID.randomUUID(), runId, (short) 1, "echo");
        step1.markRunning(Instant.now());
        step1.markCompleted("hello", "already-done", Instant.now());
        RunStep step2 = new RunStep(UUID.randomUUID(), runId, (short) 2, "echo");
        WorkflowStep def1 = new WorkflowStep(UUID.randomUUID(), workflowId, (short) 1, "first", "echo", null);
        WorkflowStep def2 = new WorkflowStep(UUID.randomUUID(), workflowId, (short) 2, "second", "echo", null);

        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of(step1, step2));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)).thenReturn(List.of(def1, def2));
        when(transactions.claim(eq(step2.getId()), any())).thenReturn(true);
        when(inputResolver.resolve(eq(2), any(), eq("hello"), any())).thenReturn("already-done");
        when(stepDispatcher.execute("echo", "already-done")).thenReturn("final-output");

        orchestrator.on(new WorkflowRunStartedEvent(runId));

        // step1 was never (re-)claimed — only step2, the one that was still pending.
        verify(transactions, times(1)).claim(any(), any());
        verify(transactions).claim(eq(step2.getId()), any());
        verify(transactions).complete(eq(runId), eq(step2.getId()), eq("already-done"), eq("final-output"), any());
    }

    @Test
    void aStepAlreadyClaimedByAnotherInvocationStopsWithoutError() {
        UUID runId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowRun run = someRun(runId, workflowId);
        RunStep step1 = new RunStep(UUID.randomUUID(), runId, (short) 1, "echo");
        step1.markRunning(Instant.now());

        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of(step1));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)).thenReturn(List.of());

        orchestrator.on(new WorkflowRunStartedEvent(runId));

        verify(transactions, never()).claim(any(), any());
        verify(stepDispatcher, never()).execute(any(), any());
    }
}
