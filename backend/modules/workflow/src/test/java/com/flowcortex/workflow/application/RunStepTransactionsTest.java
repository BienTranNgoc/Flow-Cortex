package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

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
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChildRuns childRuns = mock(ChildRuns.class);
    private final CreditAccounts credits = mock(CreditAccounts.class);
    private final RunStepTransactions transactions = new RunStepTransactions(runStepRepository,
            workflowRunRepository, credits, runUpdates, events, childRuns);

    private final UUID runId = UUID.randomUUID();
    private final UUID stepId = UUID.randomUUID();
    private final UUID parentId = UUID.randomUUID();
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
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThat(transactions.complete(runId, stepId, (short) 1, "out", null, NOW)).isTrue();

        assertThat(run.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    void aWonCompletionLeavesTheRunRunningWhileAnotherStepIsOpen() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

        assertThat(transactions.complete(runId, stepId, (short) 1, "out", null, NOW)).isTrue();

        verify(workflowRunRepository, never()).findById(runId);
    }

    @Test
    void aLostCompletionTouchesNothingElse() {
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);

        assertThat(transactions.complete(runId, stepId, (short) 1, "out", null, NOW)).isFalse();

        verify(runStepRepository, never()).countTopLevelNotCompleted(any());
        verify(workflowRunRepository, never()).findById(any());
    }

    @Test
    void completionSideEffectRunsOnlyAfterWinningTheCas() {
        AtomicBoolean registered = new AtomicBoolean();
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", "[]", NOW))
                .thenReturn(1, 0);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

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
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.completeRunIfDone(runId, NOW);

        assertThat(run.getStatus().name()).isEqualTo("COMPLETED");
        verify(runUpdates, atLeastOnce()).touched(runId);
    }

    @Test
    void completeRunIfDoneSignalsNothingWhileAStepIsOpen() {
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

        transactions.completeRunIfDone(runId, NOW);

        verify(runUpdates, never()).touched(any());
    }

    @Test
    void aWonCompletionOfTheLastStepPublishesRunCompletedOnce() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);

        verify(events, times(1)).publishEvent(new WorkflowRunCompleted(runId, run.getOwnerId()));
    }

    @Test
    void aCompletionWhileAnotherStepIsOpenPublishesNothing() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);

        verifyNoInteractions(events);
    }

    @Test
    void aLostCompletionPublishesNothing() {
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);

        verifyNoInteractions(events);
    }

    @Test
    void aFailurePublishesNothing() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "lease_expired", "boom", NOW)).thenReturn(1);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.fail(runId, stepId, (short) 1, "lease_expired", "boom", NOW);

        verifyNoInteractions(events);
    }

    @Test
    void completeRunIfDoneOnAnAlreadyCompletedRunPublishesNothing() {
        run.markCompleted(NOW);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.completeRunIfDone(runId, NOW);

        verifyNoInteractions(events);
    }

    @Test
    void aCancellingRunSettlesWithoutPublishingCompleted() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(workflowRunRepository.settleCancelled(runId, NOW)).thenReturn(1);

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);

        verifyNoInteractions(events);
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
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

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
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(0L);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.complete(runId, stepId, (short) 1, "out", null, NOW);

        verify(childRuns).finished(runId);
    }

    @Test
    void completingAStepWhileAnotherIsOpenSignalsNothingFinished() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "out", null, NOW)).thenReturn(1);
        when(runStepRepository.countTopLevelNotCompleted(runId)).thenReturn(1L);

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

    private static final String GATHERED = "{\"results\":[\"a\",null],\"texts\":[\"a\"],\"succeeded\":1,"
            + "\"failed\":1,\"total\":2,\"dropped\":0}";

    private void givenItem(short itemIndex) {
        when(runStepRepository.findLineageById(stepId)).thenReturn(Optional.of(new RunStepRepository.Lineage() {
            @Override
            public String getType() {
                return "echo";
            }

            @Override
            public UUID getParentStepId() {
                return parentId;
            }

            @Override
            public Short getParentAttempt() {
                return 1;
            }

            @Override
            public Short getItemIndex() {
                return itemIndex;
            }
        }));
    }

    private void givenParent(RunStepStatus status, String output, String outputs) {
        when(runStepRepository.findResultById(parentId)).thenReturn(Optional.of(new RunStepRepository.StepResult() {
            @Override
            public RunStepStatus getStatus() {
                return status;
            }

            @Override
            public short getAttempt() {
                return 1;
            }

            @Override
            public String getOutput() {
                return output;
            }

            @Override
            public String getOutputs() {
                return outputs;
            }
        }));
    }

    private WorkflowRun givenRunStatus(RunStatus status) {
        WorkflowRun mocked = mock(WorkflowRun.class);
        when(mocked.getStatus()).thenReturn(status);
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(mocked));
        return mocked;
    }

    private RunStepRepository.ChildRow childRow(short itemIndex, RunStepStatus status, String output) {
        RunStepRepository.ChildRow row = mock(RunStepRepository.ChildRow.class);
        when(row.getId()).thenReturn(stepId);
        when(row.getItemIndex()).thenReturn(itemIndex);
        when(row.getStatus()).thenReturn(status);
        when(row.getOutput()).thenReturn(output);
        return row;
    }

    @Test
    void childFailureRefundsAndLeavesTheRunRunning() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "boom", "no", NOW)).thenReturn(1);
        givenItem((short) 0);
        givenParent(RunStepStatus.RUNNING, null, null);
        givenRunStatus(RunStatus.RUNNING);

        assertThat(transactions.fail(runId, stepId, (short) 1, "boom", "no", NOW)).isTrue();

        verify(credits).refundStep(runId, stepId, (short) 1, "boom");
        verify(workflowRunRepository, never()).save(any());
        verify(childRuns, never()).finished(any());
        verify(childRuns, never()).stopChildOfStep(any());
        verify(runStepRepository, never()).skipPendingChildren(any());
    }

    @Test
    void aLateFailureOfAnItemAlreadyClosedRefundsNothingMore() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "boom", "no", NOW)).thenReturn(0);
        givenItem((short) 0);
        givenParent(RunStepStatus.RUNNING, null, null);

        assertThat(transactions.fail(runId, stepId, (short) 1, "boom", "no", NOW)).isFalse();

        verify(credits, never()).refundStep(any(), any(), anyShort(), any());
        verify(runStepRepository, never()).findLineageById(any());
    }

    @Test
    void aLateCompletionOfAnItemAlreadyClosedLeavesTheStepsOutputAlone() {
        when(runStepRepository.completeIfRunning(any(), any(), anyShort(), any(), any(), any())).thenReturn(0);
        givenItem((short) 0);
        givenParent(RunStepStatus.COMPLETED, GATHERED, null);

        assertThat(transactions.complete(runId, stepId, (short) 1, "late", null, NOW)).isFalse();

        verify(runStepRepository, never()).replaceCompletedOutput(any(), any(), any());
        verify(runStepRepository, never()).findLineageById(any());
        verify(credits, never()).refundStep(any(), any(), anyShort(), any());
    }

    @Test
    void childClaimUsesTheCappedClaim() {
        UUID childId = stepId;
        when(runStepRepository.claimPendingChild(childId, parentId, (short) 2, 3, NOW, NOW.plusSeconds(120), "item"))
                .thenReturn(1, 0);
        AtomicBoolean dispatched = new AtomicBoolean();

        assertThat(transactions.claimChild(runId, childId, (short) 1, NOW, NOW.plusSeconds(120), "item", parentId,
                (short) 2, 3, false, () -> dispatched.set(true)))
                .isEqualTo(RunStepTransactions.ClaimOutcome.DISPATCHED);
        assertThat(dispatched).isTrue();
        dispatched.set(false);
        assertThat(transactions.claimChild(runId, childId, (short) 1, NOW, NOW.plusSeconds(120), "item", parentId,
                (short) 2, 3, false, () -> dispatched.set(true))).isEqualTo(RunStepTransactions.ClaimOutcome.LOST);

        assertThat(dispatched).isFalse();
        verify(runStepRepository, never()).claimPending(any(), any(), any(), any());
        verify(runStepRepository, never()).claimRegeneratedChild(any(), any(), anyShort(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test
    void aRegeneratedChildClaimUsesTheRegeneratedStatement() {
        when(runStepRepository.claimRegeneratedChild(stepId, parentId, (short) 1, 2, NOW, NOW.plusSeconds(120),
                "item")).thenReturn(1);

        assertThat(transactions.claimChild(runId, stepId, (short) 2, NOW, NOW.plusSeconds(120), "item", parentId,
                (short) 1, 2, true, () -> { })).isEqualTo(RunStepTransactions.ClaimOutcome.DISPATCHED);

        verify(runStepRepository, never()).claimPendingChild(any(), any(), anyShort(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    @Test
    void aChildClaimThatCannotBePaidFailsTheChildAndLeavesTheRunRunning() {
        when(runStepRepository.claimPendingChild(stepId, parentId, (short) 1, 1, NOW, NOW.plusSeconds(120), "item"))
                .thenReturn(1);
        when(credits.enabled()).thenReturn(true);
        when(runStepRepository.findById(stepId)).thenReturn(Optional.of(new com.flowcortex.workflow.domain.RunStep(
                stepId, runId, (short) 2, "translate", "item")));
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));
        when(credits.debitForStep(any(), any(), any(), anyShort(), any())).thenReturn(false);
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "INSUFFICIENT_CREDITS",
                RunStepTransactions.NOT_ENOUGH_CREDITS, NOW)).thenReturn(1);
        givenItem((short) 0);
        givenParent(RunStepStatus.RUNNING, null, null);

        assertThat(transactions.claimChild(runId, stepId, (short) 1, NOW, NOW.plusSeconds(120), "item", parentId,
                (short) 1, 1, false, () -> { })).isEqualTo(RunStepTransactions.ClaimOutcome.FAILED);

        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void regeneratedChildCompletionRegathersACompletedParent() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 2, "b", null, NOW)).thenReturn(1);
        givenItem((short) 1);
        givenParent(RunStepStatus.COMPLETED, GATHERED, null);
        RunStepRepository.ChildRow regenerated = childRow((short) 1, RunStepStatus.COMPLETED, "b");
        when(runStepRepository.findChildStates(parentId, (short) 1)).thenReturn(List.of(regenerated));

        assertThat(transactions.complete(runId, stepId, (short) 2, "b", null, NOW)).isTrue();

        verify(runStepRepository).replaceCompletedOutput(parentId,
                "{\"results\":[\"a\",\"b\"],\"texts\":[\"a\",\"b\"],\"succeeded\":2,\"failed\":0,\"total\":2,"
                        + "\"dropped\":0}", null);
        verify(runStepRepository, never()).countTopLevelNotCompleted(any());
    }

    @Test
    void regeneratedChildFailureLeavesTheParentOutput() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 2, "boom", "no", NOW)).thenReturn(1);
        givenItem((short) 1);
        givenParent(RunStepStatus.COMPLETED, GATHERED, null);

        assertThat(transactions.fail(runId, stepId, (short) 2, "boom", "no", NOW)).isTrue();

        verify(runStepRepository, never()).replaceCompletedOutput(any(), any(), any());
        verify(credits).refundStep(runId, stepId, (short) 2, "boom");
    }

    @Test
    void runCompletionCountsTopLevelStepsOnly() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "x", null, NOW)).thenReturn(1);
        givenItem((short) 0);
        givenParent(RunStepStatus.RUNNING, null, null);
        givenRunStatus(RunStatus.RUNNING);

        assertThat(transactions.complete(runId, stepId, (short) 1, "x", null, NOW)).isTrue();

        verify(runStepRepository, never()).countTopLevelNotCompleted(any());
        verify(events, never()).publishEvent(any());
        verify(childRuns, never()).finished(any());
    }

    @Test
    void lastChildEndOfACancellingRunFailsTheIdleParentAndSettles() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "x", null, NOW)).thenReturn(1);
        when(runStepRepository.failIfRunning(parentId, runId, (short) 1, "iterate_cancelled",
                "The run was stopped before every item ran", NOW)).thenReturn(1);
        givenItem((short) 0);
        givenParent(RunStepStatus.RUNNING, null, null);
        givenRunStatus(RunStatus.CANCELLING);
        when(runStepRepository.countRunningChildren(parentId, (short) 1)).thenReturn(0L);
        when(workflowRunRepository.settleCancelled(runId, NOW)).thenReturn(1);

        assertThat(transactions.complete(runId, stepId, (short) 1, "x", null, NOW)).isTrue();

        verify(runStepRepository).failIfRunning(parentId, runId, (short) 1, "iterate_cancelled",
                "The run was stopped before every item ran", NOW);
        verify(childRuns).finished(runId);
    }

    @Test
    void aChildEndWhileASiblingStillRunsKeepsTheCancellingRunOpen() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "x", null, NOW)).thenReturn(1);
        givenItem((short) 0);
        givenParent(RunStepStatus.RUNNING, null, null);
        givenRunStatus(RunStatus.CANCELLING);
        when(runStepRepository.countRunningChildren(parentId, (short) 1)).thenReturn(1L);

        assertThat(transactions.complete(runId, stepId, (short) 1, "x", null, NOW)).isTrue();

        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }

    @Test
    void aChildEndOfACancellingRunWhoseIterateStepAlreadyFailedSettlesIt() {
        when(runStepRepository.completeIfRunning(stepId, runId, (short) 1, "x", null, NOW)).thenReturn(1);
        givenItem((short) 0);
        givenParent(RunStepStatus.ERROR, null, null);
        givenRunStatus(RunStatus.CANCELLING);
        when(workflowRunRepository.settleCancelled(runId, NOW)).thenReturn(1);

        transactions.complete(runId, stepId, (short) 1, "x", null, NOW);

        verify(workflowRunRepository).settleCancelled(runId, NOW);
        verify(childRuns).finished(runId);
    }

    @Test
    void iterateFailureSkipsItsPendingChildren() {
        when(runStepRepository.failIfRunning(stepId, runId, (short) 1, "run_timeout", "late", NOW)).thenReturn(1);
        when(runStepRepository.findLineageById(stepId)).thenReturn(Optional.of(new RunStepRepository.Lineage() {
            @Override
            public String getType() {
                return "iterate";
            }

            @Override
            public UUID getParentStepId() {
                return null;
            }

            @Override
            public Short getParentAttempt() {
                return null;
            }

            @Override
            public Short getItemIndex() {
                return null;
            }
        }));
        when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(run));

        transactions.fail(runId, stepId, (short) 1, "run_timeout", "late", NOW);

        verify(runStepRepository).skipPendingChildren(stepId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.ERROR);
    }

    @Test
    void anIdleIterateParentOfAStoppedRunFailsAndSettlesTheRun() {
        RunStepRepository.IdleParent idle = mock(RunStepRepository.IdleParent.class);
        when(idle.getId()).thenReturn(parentId);
        when(idle.getAttempt()).thenReturn((short) 2);
        when(runStepRepository.findIdleIterateParents(runId)).thenReturn(List.of(idle));
        when(runStepRepository.failIfRunning(parentId, runId, (short) 2, "iterate_cancelled",
                "The run was stopped before every item ran", NOW)).thenReturn(1);
        when(runStepRepository.findLineageById(parentId)).thenReturn(Optional.empty());
        when(workflowRunRepository.settleCancelled(runId, NOW)).thenReturn(1);

        assertThat(transactions.failIdleIterateParents(runId, NOW)).isTrue();

        verify(childRuns).finished(runId);
    }

    @Test
    void noIdleIterateParentLeavesTheStopToTheCaller() {
        when(runStepRepository.findIdleIterateParents(runId)).thenReturn(List.of());

        assertThat(transactions.failIdleIterateParents(runId, NOW)).isFalse();

        verify(runStepRepository, never()).failIfRunning(any(), any(), anyShort(), any(), any(), any());
    }
}
