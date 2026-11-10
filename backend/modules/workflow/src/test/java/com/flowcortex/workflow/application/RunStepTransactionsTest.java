package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * {@link RunStepTransactions} at the unit level: what each transition does once its CAS has
 * been decided. The REQUIRES_NEW boundaries and the SQL itself run against Postgres in {@link
 * RunStepTransactionsIT}.
 */
class RunStepTransactionsTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final RunUpdates runUpdates = mock(RunUpdates.class);
    private final ChildRuns childRuns = mock(ChildRuns.class);
    private final RunStepTransactions transactions = new RunStepTransactions(runStepRepository,
            workflowRunRepository, mock(CreditAccounts.class), runUpdates, childRuns);

    private final UUID runId = UUID.randomUUID();
    private final UUID stepId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(runId, UUID.randomUUID(), UUID.randomUUID(), "in", NOW);

    @Test
    void aWonClaimRunsTheDispatch() {
        when(runStepRepository.claimPending(stepId, NOW, NOW.plusSeconds(120), "in")).thenReturn(1);
        AtomicBoolean dispatched = new AtomicBoolean();

        RunStepTransactions.ClaimOutcome outcome =
                transactions.claim(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), "in", () -> dispatched.set(true));

        assertThat(outcome).isEqualTo(RunStepTransactions.ClaimOutcome.DISPATCHED);
        assertThat(dispatched).isTrue();
    }

    @Test
    void aLostClaimNeverDispatches() {
        when(runStepRepository.claimPending(stepId, NOW, NOW.plusSeconds(120), "in")).thenReturn(0);
        AtomicBoolean dispatched = new AtomicBoolean();

        RunStepTransactions.ClaimOutcome outcome =
                transactions.claim(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), "in", () -> dispatched.set(true));

        assertThat(outcome).isEqualTo(RunStepTransactions.ClaimOutcome.LOST);
        assertThat(dispatched).isFalse();
    }

    @Test
    void aDispatchThatReportsAStepFailureErrorsTheStepAndTheRunInTheClaim() {
        when(runStepRepository.claimPending(stepId, NOW, NOW.plusSeconds(120), "in")).thenReturn(1);
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "media_disabled", "off", NOW)).thenReturn(1);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        RunStepTransactions.ClaimOutcome outcome = transactions.claim(runId, stepId, (short) 1, NOW,
                NOW.plusSeconds(120), "in", () -> {
                    throw new StepFailedException("media_disabled", "off");
                });

        assertThat(outcome).isEqualTo(RunStepTransactions.ClaimOutcome.FAILED);
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        assertThat(run.getErrorMessage()).isEqualTo("off");
    }

    @Test
    void aWonCompletionOfTheLastOpenStepCompletesTheRun() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThat(transactions.complete(runId, stepId, (short) 1, "out", null, NOW)).isTrue();

        assertThat(run.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    void aWonCompletionLeavesTheRunRunningWhileAnotherStepIsOpen() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(1L);

        assertThat(transactions.complete(runId, stepId, (short) 1, "out", null, NOW)).isTrue();

        verify(workflowRunRepository, never()).findById(runId);
    }

    @Test
    void aLostCompletionTouchesNothingElse() {
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);

        assertThat(transactions.complete(runId, stepId, (short) 1, "out", null, NOW)).isFalse();

        verify(runStepRepository, never()).countByRunIdAndStatusNot(any(), any());
        verify(workflowRunRepository, never()).findById(any());
    }

    @Test
    void completionSideEffectRunsOnlyAfterWinningTheCas() {
        AtomicBoolean registered = new AtomicBoolean();
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", "[]", NOW))
                .thenReturn(1, 0);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(1L);

        assertThat(transactions.complete(runId, stepId, (short) 1, "out", "[]", NOW,
                () -> registered.set(true))).isTrue();
        assertThat(registered).isTrue();

        registered.set(false);
        assertThat(transactions.complete(runId, stepId, (short) 1, "out", "[]", NOW,
                () -> registered.set(true))).isFalse();
        assertThat(registered).isFalse();
    }

    @Test
    void aWonFailureErrorsTheRunWithTheStepsMessage() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "lease_expired", "boom", NOW)).thenReturn(1);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThat(transactions.fail(runId, stepId, (short) 1, "lease_expired", "boom", NOW)).isTrue();

        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        assertThat(run.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void aLostFailureLeavesTheRunAlone() {
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);

        assertThat(transactions.fail(runId, stepId, (short) 1, "lease_expired", "boom", NOW)).isFalse();

        verify(workflowRunRepository, never()).findById(any());
    }

    @Test
    void completeRunIfDoneCompletesARunningRunWhoseStepsAreAllCompleted() {
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.completeRunIfDone(runId, NOW);

        assertThat(run.getStatus().name()).isEqualTo("COMPLETED");
        verify(runUpdates, atLeastOnce()).touched(runId);
    }

    @Test
    void completeRunIfDoneSignalsNothingWhileAStepIsOpen() {
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(1L);

        transactions.completeRunIfDone(runId, NOW);

        verify(runUpdates, never()).touched(any());
    }

    @Test
    void aWonClaimSignalsTheRunAndALostOneDoesNot() {
        when(runStepRepository.claimPending(stepId, NOW, NOW.plusSeconds(120), "in")).thenReturn(1, 0);

        transactions.claim(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), "in", () -> { });
        verify(runUpdates).touched(runId);

        transactions.claim(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), "in", () -> { });
        verify(runUpdates).touched(runId);
    }

    @Test
    void aWonCompletionSignalsTheRunAndALostOneDoesNot() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1, 0);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(1L);

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);
        verify(runUpdates, atLeastOnce()).touched(runId);
        clearInvocations(runUpdates);

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);
        verify(runUpdates, never()).touched(any());
    }

    @Test
    void aWonFailureSignalsTheRunAndALostOneDoesNot() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "lease_expired", "boom", NOW)).thenReturn(1, 0);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.fail(runId, stepId, (short) 1, "lease_expired", "boom", NOW);
        verify(runUpdates, atLeastOnce()).touched(runId);
        clearInvocations(runUpdates);

        transactions.fail(runId, stepId, (short) 1, "lease_expired", "boom", NOW);
        verify(runUpdates, never()).touched(any());
    }

    @Test
    void aFailureThatSettlesACancellingRunSignalsIt() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "lease_expired", "boom", NOW)).thenReturn(1);
        when(workflowRunRepository.settleCancelled(runId, NOW)).thenReturn(1);

        transactions.fail(runId, stepId, (short) 1, "lease_expired", "boom", NOW);

        verify(runUpdates, atLeastOnce()).touched(runId);
    }

    @Test
    void aLeaseExtensionSignalsOnlyWhenItCarriesAPercent() {
        when(runStepRepository.extendLease(any(), anyShort(), any(), any(), any(), anyShort(), any())).thenReturn(1);

        transactions.extendLease(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), null, (short) 0,
                NOW.plusSeconds(300));
        verify(runUpdates, never()).touched(any());

        transactions.extendLease(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), (short) 40, (short) 0,
                NOW.plusSeconds(300));
        verify(runUpdates).touched(runId);
    }

    @Test
    void aLeaseExtensionThatLostTheStepSignalsNothing() {
        when(runStepRepository.extendLease(any(), anyShort(), any(), any(), any(), anyShort(), any())).thenReturn(0);

        transactions.extendLease(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), (short) 40, (short) 0,
                NOW.plusSeconds(300));

        verify(runUpdates, never()).touched(any());
    }

    @Test
    void completingTheLastStepSignalsTheRunFinished() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);

        verify(childRuns).finished(runId);
    }

    @Test
    void completingAStepWhileAnotherIsOpenSignalsNothingFinished() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED)).thenReturn(1L);

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);

        verify(childRuns, never()).finished(any());
    }

    @Test
    void aFailureThatErrorsTheRunSignalsIt() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "lease_expired", "boom", NOW)).thenReturn(1);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.fail(runId, stepId, (short) 1, "lease_expired", "boom", NOW);

        verify(childRuns).finished(runId);
    }

    @Test
    void aFailureThatSettlesACancellingRunSignalsItFinished() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "lease_expired", "boom", NOW)).thenReturn(1);
        when(workflowRunRepository.settleCancelled(runId, NOW)).thenReturn(1);

        transactions.fail(runId, stepId, (short) 1, "lease_expired", "boom", NOW);

        verify(childRuns).finished(runId);
    }

    @Test
    void aWinningFailureStopsTheStepsChild() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "run_timeout", "late", NOW)).thenReturn(1);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.fail(runId, stepId, (short) 1, "run_timeout", "late", NOW);

        verify(childRuns).stopChildOfStep(stepId);
    }

    @Test
    void aLostFailureStopsNothing() {
        when(runStepRepository.failIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);

        transactions.fail(runId, stepId, (short) 1, "run_timeout", "late", NOW);

        verify(childRuns, never()).stopChildOfStep(any());
        verify(childRuns, never()).finished(any());
    }

    @Test
    void aPickupThatAppliesSignalsTheRunAndOneThatDoesNotDoesNot() {
        when(runStepRepository.recordPickup(any(), anyShort(), any(), any(), any(), anyShort())).thenReturn(1, 0);

        transactions.recordPickup(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), NOW.plusSeconds(300),
                (short) 0);
        verify(runUpdates).touched(runId);

        transactions.recordPickup(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), NOW.plusSeconds(300),
                (short) 0);
        verify(runUpdates).touched(runId);
    }
}
