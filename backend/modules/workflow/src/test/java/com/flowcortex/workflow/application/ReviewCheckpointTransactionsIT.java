package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pausing at an approval checkpoint against a stop, on a real Postgres so the run's row lock is
 * real. The advancer hands {@code pause} the run and step it read earlier; a stop that lands in
 * between must win. Not wrapped in a test transaction, so each REQUIRES_NEW really commits.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ReviewCheckpointTransactions.class)
@Testcontainers
class ReviewCheckpointTransactionsIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private ReviewCheckpointTransactions checkpoints;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private RunStepRepository runStepRepository;

    @Autowired
    private ReviewTaskRepository reviewTaskRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** A running run whose only step is a pending checkpoint, as the advancer reads it. */
    private RunStep pendingCheckpoint() {
        Workflow workflow = workflowRepository.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "it-" + UUID.randomUUID(), false));
        WorkflowRun run = workflowRunRepository.saveAndFlush(
                new WorkflowRun(UUID.randomUUID(), workflow.getId(), UUID.randomUUID(), "in", NOW));
        return runStepRepository.saveAndFlush(
                new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "APPROVAL_CHECKPOINT"));
    }

    /** The statements a member's stop runs, in its order. */
    private void stop(WorkflowRun run) {
        assertThat(workflowRunRepository.requestCancel(run.getId(), run.getOwnerId())).isOne();
        runStepRepository.skipPending(run.getId());
        workflowRunRepository.settleCancelled(run.getId(), NOW);
    }

    private void assertStillCancelled(RunStep step) {
        WorkflowRun after = workflowRunRepository.findById(step.getRunId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(after.getFinishedAt()).isEqualTo(NOW);
        assertThat(runStepRepository.findById(step.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStepStatus.SKIPPED);
        assertThat(reviewTaskRepository.findByRunStepId(step.getId())).isEmpty();
    }

    @Test
    void aPauseWithASnapshotReadBeforeTheStopLeavesTheRunCancelled() {
        RunStep step = pendingCheckpoint();
        WorkflowRun staleRun = workflowRunRepository.findById(step.getRunId()).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> stop(staleRun));

        boolean paused = checkpoints.pause(staleRun, step, "draft", NOW.plusSeconds(1));

        assertThat(paused).isFalse();
        assertStillCancelled(step);
    }

    @Test
    void aPauseRacingAnUncommittedStopWaitsForItAndLeavesTheRunCancelled() throws Exception {
        RunStep step = pendingCheckpoint();
        WorkflowRun staleRun = workflowRunRepository.findById(step.getRunId()).orElseThrow();
        CountDownLatch stopWritten = new CountDownLatch(1);
        CountDownLatch commitStop = new CountDownLatch(1);

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() ->
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    stop(staleRun);
                    stopWritten.countDown();
                    try {
                        assertThat(commitStop.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
        assertThat(stopWritten.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Boolean> pausing = CompletableFuture.supplyAsync(() ->
                checkpoints.pause(staleRun, step, "draft", NOW.plusSeconds(1)));
        // Lets the pause reach the run's lock while the stop still holds it; the outcome must not depend on it.
        Thread.sleep(300);
        commitStop.countDown();

        stopping.get(10, TimeUnit.SECONDS);
        assertThat(pausing.get(10, TimeUnit.SECONDS)).isFalse();
        assertStillCancelled(step);
    }
}
