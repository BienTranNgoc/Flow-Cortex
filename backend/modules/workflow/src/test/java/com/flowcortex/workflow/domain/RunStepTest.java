package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The {@code run_step} status ladder: the only legal transitions are
 * {@code pending -> running -> completed} or {@code pending -> running ->
 * error}; every other call is rejected.
 */
class RunStepTest {

    private RunStep newPendingStep() {
        return new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo");
    }

    @Test
    void pendingStepStartsWithNoTimestampsAndPendingStatus() {
        RunStep step = newPendingStep();

        assertThat(step.getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(step.getStartedAt()).isNull();
        assertThat(step.getFinishedAt()).isNull();
    }

    @Test
    void pendingToRunningIsLegal() {
        RunStep step = newPendingStep();
        Instant now = Instant.now();

        step.markRunning(now);

        assertThat(step.getStatus()).isEqualTo(RunStepStatus.RUNNING);
        assertThat(step.getStartedAt()).isEqualTo(now);
    }

    @Test
    void runningToCompletedIsLegalAndRecordsInputAndOutput() {
        RunStep step = newPendingStep();
        step.markRunning(Instant.now());
        Instant finishedAt = Instant.now();

        step.markCompleted("resolved-input", "the-output", finishedAt);

        assertThat(step.getStatus()).isEqualTo(RunStepStatus.COMPLETED);
        assertThat(step.getInput()).isEqualTo("resolved-input");
        assertThat(step.getOutput()).isEqualTo("the-output");
        assertThat(step.getFinishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void runningToErrorIsLegalAndRecordsErrorMessage() {
        RunStep step = newPendingStep();
        step.markRunning(Instant.now());
        Instant finishedAt = Instant.now();

        step.markError("resolved-input", "boom", finishedAt);

        assertThat(step.getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(step.getInput()).isEqualTo("resolved-input");
        assertThat(step.getErrorMessage()).isEqualTo("boom");
        assertThat(step.getFinishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void errorMayBeRecordedWithoutAResolvedInput() {
        // An unresolved/dangling input_ref fails before any input value
        // exists — markError must accept a null resolvedInput.
        RunStep step = newPendingStep();
        step.markRunning(Instant.now());

        step.markError(null, "dangling reference", Instant.now());

        assertThat(step.getInput()).isNull();
        assertThat(step.getErrorMessage()).isEqualTo("dangling reference");
    }

    @Test
    void completingAStillPendingStepIsIllegal() {
        RunStep step = newPendingStep();

        assertThatThrownBy(() -> step.markCompleted("in", "out", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void erroringAStillPendingStepIsIllegal() {
        RunStep step = newPendingStep();

        assertThatThrownBy(() -> step.markError("in", "boom", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reRunningAnAlreadyRunningStepIsIllegal() {
        RunStep step = newPendingStep();
        step.markRunning(Instant.now());

        assertThatThrownBy(() -> step.markRunning(Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reRunningAnAlreadyCompletedStepIsIllegal() {
        RunStep step = newPendingStep();
        step.markRunning(Instant.now());
        step.markCompleted("in", "out", Instant.now());

        assertThatThrownBy(() -> step.markRunning(Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completingAnAlreadyErroredStepIsIllegal() {
        RunStep step = newPendingStep();
        step.markRunning(Instant.now());
        step.markError("in", "boom", Instant.now());

        assertThatThrownBy(() -> step.markCompleted("in", "out2", Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aNewStepHasAttemptOneNoDeliveriesAndNoLease() {
        RunStep step = newPendingStep();

        assertThat(step.getAttempt()).isEqualTo((short) 1);
        assertThat(step.getDeliveryCount()).isZero();
        assertThat(step.getPickedUpAt()).isNull();
        assertThat(step.getLeaseUntil()).isNull();
        assertThat(step.getErrorCode()).isNull();
    }

    @Test
    void claimRecordsTheQueueDeadlineAndLeavesTheStepWaitingForPickup() {
        RunStep step = newPendingStep();
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        step.markRunning(now, now.plusSeconds(120));

        assertThat(step.getQueueDeadline()).isEqualTo(now.plusSeconds(120));
        assertThat(step.getPickedUpAt()).isNull();
    }

    @Test
    void pickupSetsLeaseRunDeadlineAndDeliveryCountAndKeepsTheFirstPickupTime() {
        RunStep step = newPendingStep();
        Instant first = Instant.parse("2026-09-30T10:00:00Z");
        Instant redelivery = first.plusSeconds(200);
        step.markRunning(first, first.plusSeconds(1800));

        step.recordPickup(first, first.plusSeconds(120), first.plusSeconds(300), (short) 0);
        step.recordPickup(redelivery, redelivery.plusSeconds(120), redelivery.plusSeconds(300), (short) 1);

        assertThat(step.getPickedUpAt()).isEqualTo(first);
        assertThat(step.getLeaseUntil()).isEqualTo(redelivery.plusSeconds(120));
        assertThat(step.getRunDeadline()).isEqualTo(redelivery.plusSeconds(300));
        assertThat(step.getDeliveryCount()).isEqualTo((short) 1);
    }

    @Test
    void aHeartbeatExtendsTheLeaseAndProgressButNotAnExistingRunDeadline() {
        RunStep step = newPendingStep();
        Instant at = Instant.parse("2026-09-30T10:00:00Z");
        step.markRunning(at, at.plusSeconds(1800));
        step.recordPickup(at, at.plusSeconds(120), at.plusSeconds(300), (short) 0);

        step.extendLease(at.plusSeconds(30), at.plusSeconds(150), (short) 40, (short) 0, at.plusSeconds(330));

        assertThat(step.getLeaseUntil()).isEqualTo(at.plusSeconds(150));
        assertThat(step.getProgressPercent()).isEqualTo((short) 40);
        assertThat(step.getRunDeadline()).isEqualTo(at.plusSeconds(300));
    }

    @Test
    void aHeartbeatThatOvertakesTheStartedMessageStillCountsAsPickup() {
        RunStep step = newPendingStep();
        Instant at = Instant.parse("2026-09-30T10:00:00Z");
        step.markRunning(at, at.plusSeconds(1800));

        step.extendLease(at, at.plusSeconds(120), null, (short) 0, at.plusSeconds(300));

        assertThat(step.getPickedUpAt()).isEqualTo(at);
        assertThat(step.getRunDeadline()).isEqualTo(at.plusSeconds(300));
        assertThat(step.getProgressPercent()).isNull();
    }

    @Test
    void progressOutsideZeroToHundredIsRejected() {
        RunStep step = newPendingStep();
        Instant at = Instant.now();
        step.markRunning(at);

        assertThatThrownBy(() -> step.extendLease(at, at.plusSeconds(120), (short) 101, (short) 0, at))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anErrorRecordsItsCode() {
        RunStep step = newPendingStep();
        step.markRunning(Instant.now());

        step.markError(null, "lease_expired", "No heartbeat within the lease", Instant.now());

        assertThat(step.getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(step.getErrorCode()).isEqualTo("lease_expired");
        assertThat(step.getErrorMessage()).isEqualTo("No heartbeat within the lease");
    }

    @Test
    void pickupOfAPendingStepIsIllegal() {
        RunStep step = newPendingStep();
        Instant at = Instant.now();

        assertThatThrownBy(() -> step.recordPickup(at, at, at, (short) 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aChildIsPendingAtAttemptOneWithItsParentColumnsAndNoConfig() {
        UUID runId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();

        RunStep child = RunStep.child(runId, (short) 3, "echo", parentId, (short) 2, (short) 4, "item");

        assertThat(child.getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(child.getAttempt()).isEqualTo((short) 1);
        assertThat(child.getRunId()).isEqualTo(runId);
        assertThat(child.getStepOrder()).isEqualTo((short) 3);
        assertThat(child.getParentStepId()).isEqualTo(parentId);
        assertThat(child.getParentAttempt()).isEqualTo((short) 2);
        assertThat(child.getItemIndex()).isEqualTo((short) 4);
        assertThat(child.getInput()).isEqualTo("item");
        assertThat(child.getConfig()).isNull();
        assertThat(child.getInputRef()).isNull();
        assertThat(newPendingStep().getParentStepId()).as("a top-level step has no parent").isNull();
    }

    @Test
    void aCompletedCopyCarriesTheEarlierResultIntoTheNextParentAttempt() {
        RunStep earlier = RunStep.child(UUID.randomUUID(), (short) 3, "echo", UUID.randomUUID(), (short) 1, (short) 4,
                "item");
        Instant started = Instant.parse("2026-11-20T10:00:00Z");
        Instant finished = Instant.parse("2026-11-20T10:00:05Z");
        earlier.markRunning(started);
        earlier.markCompleted("item", "out", finished, "[{\"name\":\"a.wav\"}]");

        RunStep copy = RunStep.completedCopy(earlier, (short) 2);

        assertThat(copy.getId()).isNotEqualTo(earlier.getId());
        assertThat(copy.getStatus()).isEqualTo(RunStepStatus.COMPLETED);
        assertThat(copy.getAttempt()).isEqualTo((short) 1);
        assertThat(copy.getRunId()).isEqualTo(earlier.getRunId());
        assertThat(copy.getStepOrder()).isEqualTo(earlier.getStepOrder());
        assertThat(copy.getType()).isEqualTo("echo");
        assertThat(copy.getParentStepId()).isEqualTo(earlier.getParentStepId());
        assertThat(copy.getParentAttempt()).isEqualTo((short) 2);
        assertThat(copy.getItemIndex()).isEqualTo((short) 4);
        assertThat(copy.getInput()).isEqualTo("item");
        assertThat(copy.getOutput()).isEqualTo("out");
        assertThat(copy.getOutputs()).isEqualTo("[{\"name\":\"a.wav\"}]");
        assertThat(copy.getStartedAt()).isEqualTo(started);
        assertThat(copy.getFinishedAt()).isEqualTo(finished);
    }

    @Test
    void aHeartbeatOnAFinishedStepIsIllegal() {
        RunStep step = newPendingStep();
        Instant at = Instant.now();
        step.markRunning(at);
        step.markCompleted("in", "out", at);

        assertThatThrownBy(() -> step.extendLease(at, at, null, (short) 0, at))
                .isInstanceOf(IllegalStateException.class);
    }
}
