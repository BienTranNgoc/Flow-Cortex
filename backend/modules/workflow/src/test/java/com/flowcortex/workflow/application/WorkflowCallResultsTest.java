package com.flowcortex.workflow.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
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
import org.springframework.test.util.ReflectionTestUtils;

class WorkflowCallResultsTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunStepRepository steps = mock(RunStepRepository.class);
    private final RunStepTransactions transactions = mock(RunStepTransactions.class);
    private final RunAdvancer advancer = mock(RunAdvancer.class);
    private final WorkflowCallResults results =
            new WorkflowCallResults(runs, steps, transactions, advancer, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID parentRunId = UUID.randomUUID();
    private final UUID parentStepId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();
    private final WorkflowRun child = WorkflowRun.child(childId, UUID.randomUUID(), UUID.randomUUID(), "in", "{}", NOW,
            parentRunId, parentStepId, (short) 2, (short) 1);

    private RunStep completedStep(int order, String output) {
        RunStep step = new RunStep(UUID.randomUUID(), childId, (short) order, "echo");
        step.markRunning(NOW);
        step.markCompleted("in", output, NOW);
        return step;
    }

    @Test
    void aCompletedChildCompletesTheStepWithItsLastOutputThenAdvancesTheParent() {
        child.markCompleted(NOW);
        when(runs.findById(childId)).thenReturn(Optional.of(child));
        when(steps.findByRunIdOrderByStepOrderAsc(childId))
                .thenReturn(List.of(completedStep(1, "a"), completedStep(2, "b")));
        when(transactions.complete(parentRunId, parentStepId, (short) 2, "b", null, NOW)).thenReturn(true);

        results.on(new ChildRunFinishedEvent(childId));

        verify(transactions).complete(parentRunId, parentStepId, (short) 2, "b", null, NOW);
        verify(advancer).advance(parentRunId);
    }

    @Test
    void aCompletionThatLostTheRaceDoesNotAdvance() {
        child.markCompleted(NOW);
        when(runs.findById(childId)).thenReturn(Optional.of(child));
        when(steps.findByRunIdOrderByStepOrderAsc(childId)).thenReturn(List.of(completedStep(1, "a")));
        when(transactions.complete(any(), any(), anyShort(), any(), any(), any())).thenReturn(false);

        results.on(new ChildRunFinishedEvent(childId));

        verify(advancer, never()).advance(any());
    }

    @Test
    void anErroredChildFailsTheStepWithItsMessage() {
        child.markError("boom", NOW);
        when(runs.findById(childId)).thenReturn(Optional.of(child));
        when(steps.findByRunIdOrderByStepOrderAsc(childId)).thenReturn(List.of());
        when(transactions.fail(eq(parentRunId), eq(parentStepId), eq((short) 2), eq("workflow_call_child_failed"),
                contains("boom"), eq(NOW))).thenReturn(true);

        results.on(new ChildRunFinishedEvent(childId));

        verify(transactions).fail(eq(parentRunId), eq(parentStepId), eq((short) 2), eq("workflow_call_child_failed"),
                contains("boom"), eq(NOW));
        verify(advancer).advance(parentRunId);
    }

    @Test
    void aCancelledChildFailsTheStep() {
        ReflectionTestUtils.setField(child, "status", RunStatus.CANCELLED);
        when(runs.findById(childId)).thenReturn(Optional.of(child));
        when(steps.findByRunIdOrderByStepOrderAsc(childId)).thenReturn(List.of());

        results.on(new ChildRunFinishedEvent(childId));

        verify(transactions).fail(eq(parentRunId), eq(parentStepId), eq((short) 2),
                eq("workflow_call_child_cancelled"), any(), eq(NOW));
    }

    @Test
    void aChildStillRunningChangesNothing() {
        when(runs.findById(childId)).thenReturn(Optional.of(child));
        when(steps.findByRunIdOrderByStepOrderAsc(childId)).thenReturn(List.of());

        results.on(new ChildRunFinishedEvent(childId));

        verify(transactions, never()).complete(any(), any(), anyShort(), any(), any(), any());
        verify(transactions, never()).fail(any(), any(), anyShort(), any(), any(), any());
        verify(advancer, never()).advance(any());
    }

    @Test
    void aRunWithNoParentOrNoLongerThereChangesNothing() {
        WorkflowRun root = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in", NOW);
        root.markCompleted(NOW);
        when(runs.findById(root.getId())).thenReturn(Optional.of(root));

        results.on(new ChildRunFinishedEvent(root.getId()));
        results.on(new ChildRunFinishedEvent(UUID.randomUUID()));

        verify(transactions, never()).complete(any(), any(), anyShort(), any(), any(), any());
        verify(transactions, never()).fail(any(), any(), anyShort(), any(), any(), any());
        verify(advancer, never()).advance(any());
    }
}
