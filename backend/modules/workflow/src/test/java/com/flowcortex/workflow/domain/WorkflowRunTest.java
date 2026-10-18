package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The {@code workflow_run} entity. */
class WorkflowRunTest {

    @Test
    void constructorStartsInRunningStatusWithNoFinishTimeOrError() {
        Instant startedAt = Instant.now();

        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "the-input",
                startedAt);

        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getInput()).isEqualTo("the-input");
        assertThat(run.getStartedAt()).isEqualTo(startedAt);
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getErrorMessage()).isNull();
    }

    @Test
    void markCompletedSetsStatusAndFinishedAt() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        Instant finishedAt = Instant.now();

        run.markCompleted(finishedAt);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getFinishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void markErrorSetsStatusErrorMessageAndFinishedAt() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        Instant finishedAt = Instant.now();

        run.markError("boom", finishedAt);

        assertThat(run.getStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(run.getErrorMessage()).isEqualTo("boom");
        assertThat(run.getFinishedAt()).isEqualTo(finishedAt);
    }

    @Test
    void onlyARunningRunCanWaitForApproval() {
        WorkflowRun running = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        WorkflowRun finished = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        Instant finishedAt = Instant.now();
        finished.markError("boom", finishedAt);

        assertThat(running.markWaitingApproval()).isTrue();
        assertThat(running.getStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(finished.markWaitingApproval()).isFalse();
        assertThat(finished.getStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(finished.getFinishedAt()).isEqualTo(finishedAt);
    }
}
