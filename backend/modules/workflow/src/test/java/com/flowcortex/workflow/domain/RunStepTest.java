package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Task 5.3 — the {@code run_step} status ladder (design.md D5, R-043): the
 * only legal transitions are {@code pending -> running -> completed} or
 * {@code pending -> running -> error}; every other call is rejected.
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
        // An unresolved/dangling input_ref (R-042) fails before any input
        // value exists — markError must accept a null resolvedInput.
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
}
