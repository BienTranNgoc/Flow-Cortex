package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Task 5.4 — {@link RunStepTransactions}, at the unit level (the REQUIRES_NEW
 * propagation itself is proven at the Testcontainers level by {@link
 * RunStepClaimConcurrencyIT} / {@code WorkflowRunEngineIT}; this covers the
 * business logic each method performs once inside its own transaction).
 */
class RunStepTransactionsTest {

    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final RunStepTransactions transactions =
            new RunStepTransactions(runStepRepository, workflowRunRepository);

    @Test
    void claimDelegatesToTheAtomicUpdateAndReturnsTrueOnRowCountOne() {
        UUID stepId = UUID.randomUUID();
        Instant now = Instant.now();
        when(runStepRepository.claimPending(stepId, now)).thenReturn(1);

        assertThat(transactions.claim(stepId, now)).isTrue();
    }

    @Test
    void claimReturnsFalseOnRowCountZero() {
        UUID stepId = UUID.randomUUID();
        Instant now = Instant.now();
        when(runStepRepository.claimPending(stepId, now)).thenReturn(0);

        assertThat(transactions.claim(stepId, now)).isFalse();
    }

    @Test
    void completeMarksTheRunCompletedWhenNoOtherStepIsOpen() {
        UUID runId = UUID.randomUUID();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "echo");
        step.markRunning(Instant.now());
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.complete(runId, step.getId(), "in", "out", Instant.now());

        assertThat(step.getStatus()).isEqualTo(RunStepStatus.COMPLETED);
        assertThat(run.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    void completeLeavesTheRunRunningWhenAnotherStepIsStillOpen() {
        UUID runId = UUID.randomUUID();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "echo");
        step.markRunning(Instant.now());
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(1L);

        transactions.complete(runId, step.getId(), "in", "out", Instant.now());

        assertThat(step.getStatus()).isEqualTo(RunStepStatus.COMPLETED);
        verify(workflowRunRepository, times(0)).findById(runId);
    }

    @Test
    void failMarksBothTheStepAndTheRunAsError() {
        UUID runId = UUID.randomUUID();
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) 1, "echo");
        step.markRunning(Instant.now());
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        when(runStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.fail(runId, step.getId(), "in", "boom", Instant.now());

        assertThat(step.getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(step.getErrorMessage()).isEqualTo("boom");
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        assertThat(run.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void completeOnAnUnknownStepIsNotFound() {
        when(runStepRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactions.complete(UUID.randomUUID(), UUID.randomUUID(), "in", "out",
                Instant.now())).isInstanceOf(NotFoundException.class);
    }
}
