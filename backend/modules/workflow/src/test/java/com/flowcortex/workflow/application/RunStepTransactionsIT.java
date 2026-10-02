package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The step transitions against a real Postgres on the Flyway-migrated schema (so its CHECK
 * constraints and indexes apply): the claim and whatever it dispatches share one transaction,
 * and every later transition is a row-count CAS on {@code status = 'running'} and the attempt.
 * Not wrapped in a test transaction, so each REQUIRES_NEW really commits.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(RunStepTransactions.class)
@Testcontainers
class RunStepTransactionsIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private RunStepRepository runStepRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private RunStepTransactions transactions;

    @Autowired
    private DataSource dataSource;

    private RunStep newPendingStep() {
        Workflow workflow = workflowRepository.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "it-" + UUID.randomUUID(), false));
        WorkflowRun run = workflowRunRepository.saveAndFlush(
                new WorkflowRun(UUID.randomUUID(), workflow.getId(), UUID.randomUUID(), "in", NOW));
        return runStepRepository.saveAndFlush(new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "probe"));
    }

    private RunStep reload(RunStep step) {
        return runStepRepository.findById(step.getId()).orElseThrow();
    }

    private RunStep claimed() {
        RunStep step = newPendingStep();
        transactions.claim(step.getRunId(), step.getId(), (short) 1, NOW, NOW.plusSeconds(1800), "in", () -> { });
        return step;
    }

    @Test
    void theDispatchRunsInsideTheClaimTransactionSoItsFailureUndoesTheClaim() {
        RunStep step = newPendingStep();
        AtomicBoolean inTransaction = new AtomicBoolean();

        assertThatThrownBy(() -> transactions.claim(step.getRunId(), step.getId(), (short) 1, NOW,
                NOW.plusSeconds(1800), "in", () -> {
                    inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    throw new IllegalStateException("broker record failed");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(inTransaction).isTrue();
        assertThat(reload(step).getStatus()).isEqualTo(RunStepStatus.PENDING);
    }

    @Test
    void aStepFailureReportedByTheDispatchIsCommittedWithTheClaim() {
        RunStep step = newPendingStep();

        RunStepTransactions.ClaimOutcome outcome = transactions.claim(step.getRunId(), step.getId(), (short) 1, NOW,
                NOW.plusSeconds(1800), "in", () -> {
                    throw new StepFailedException("contract_violation", "inputs: must not be empty");
                });

        RunStep reloaded = reload(step);
        assertThat(outcome).isEqualTo(RunStepTransactions.ClaimOutcome.FAILED);
        assertThat(reloaded.getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(reloaded.getErrorCode()).isEqualTo("contract_violation");
        assertThat(reloaded.getInput()).isEqualTo("in");
        assertThat(workflowRunRepository.findById(step.getRunId()).orElseThrow().getErrorMessage())
                .isEqualTo("inputs: must not be empty");
    }

    @Test
    void theClaimStampsTheQueueDeadlineAndTheResolvedInput() {
        RunStep reloaded = reload(claimed());

        assertThat(reloaded.getStatus()).isEqualTo(RunStepStatus.RUNNING);
        assertThat(reloaded.getQueueDeadline()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(reloaded.getInput()).isEqualTo("in");
        assertThat(reloaded.getPickedUpAt()).isNull();
    }

    @Test
    void aResultAppliesOnlyToTheRunningStepOfTheSameAttempt() {
        RunStep step = claimed();

        assertThat(transactions.complete(step.getRunId(), step.getId(), (short) 2, "out", null, NOW)).isFalse();
        assertThat(transactions.complete(UUID.randomUUID(), step.getId(), (short) 1, "out", null, NOW)).isFalse();
        assertThat(transactions.fail(step.getRunId(), step.getId(), (short) 1, "lease_expired", "gone", NOW)).isTrue();
        assertThat(transactions.complete(step.getRunId(), step.getId(), (short) 1, "late", null, NOW)).isFalse();

        RunStep reloaded = reload(step);
        assertThat(reloaded.getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(reloaded.getErrorCode()).isEqualTo("lease_expired");
        assertThat(reloaded.getOutput()).isNull();
    }

    @Test
    void aCompletionStoresTheOutputsAsJson() {
        RunStep step = claimed();
        String outputs = "[{\"name\":\"video-details.json\",\"bucket\":\"flowcortex-media\","
                + "\"objectKey\":\"runs/x/steps/1/video-details.json\",\"mime\":\"application/json\",\"bytes\":42}]";

        assertThat(transactions.complete(step.getRunId(), step.getId(), (short) 1, "{}", outputs, NOW)).isTrue();

        assertThat(reload(step).getOutputs()).contains("\"name\"").contains("video-details.json");
        assertThat(workflowRunRepository.findById(step.getRunId()).orElseThrow().getStatus().name())
                .isEqualTo("COMPLETED");
    }

    @Test
    void aHeartbeatNeverMovesTheRunDeadlineButEachStartedDoes() {
        RunStep step = claimed();
        Instant t0 = NOW.plusSeconds(10);

        assertThat(transactions.extendLease(step.getId(), (short) 1, t0, t0.plusSeconds(120), (short) 10, (short) 0,
                t0.plusSeconds(300))).isTrue();
        assertThat(transactions.extendLease(step.getId(), (short) 1, t0.plusSeconds(30), t0.plusSeconds(150), null,
                (short) 0, t0.plusSeconds(330))).isTrue();
        RunStep afterHeartbeats = reload(step);
        assertThat(afterHeartbeats.getPickedUpAt()).isEqualTo(t0);
        assertThat(afterHeartbeats.getRunDeadline()).isEqualTo(t0.plusSeconds(300));
        assertThat(afterHeartbeats.getLeaseUntil()).isEqualTo(t0.plusSeconds(150));
        assertThat(afterHeartbeats.getProgressPercent()).isEqualTo((short) 10);

        Instant redelivered = t0.plusSeconds(200);
        assertThat(transactions.recordPickup(step.getId(), (short) 1, redelivered, redelivered.plusSeconds(120),
                redelivered.plusSeconds(300), (short) 1)).isTrue();
        RunStep afterRedelivery = reload(step);
        assertThat(afterRedelivery.getPickedUpAt()).isEqualTo(t0);
        assertThat(afterRedelivery.getRunDeadline()).isEqualTo(redelivered.plusSeconds(300));
        assertThat(afterRedelivery.getDeliveryCount()).isEqualTo((short) 1);
    }

    @Test
    void progressForAFinishedStepIsIgnored() {
        RunStep step = claimed();
        transactions.fail(step.getRunId(), step.getId(), (short) 1, "queue_timeout", "late", NOW);

        assertThat(transactions.recordPickup(step.getId(), (short) 1, NOW, NOW, NOW, (short) 0)).isFalse();
        assertThat(reload(step).getPickedUpAt()).isNull();
    }

    @Test
    void theReaperSeesOnlyRunningStepsWithAPassedBound() {
        RunStep overdue = newPendingStep();
        transactions.claim(overdue.getRunId(), overdue.getId(), (short) 1, NOW, NOW.minusSeconds(1), "in", () -> { });
        RunStep fresh = newPendingStep();
        transactions.claim(fresh.getRunId(), fresh.getId(), (short) 1, NOW, NOW.plusSeconds(1800), "in", () -> { });
        RunStep leaseGone = claimed();
        transactions.recordPickup(leaseGone.getId(), (short) 1, NOW, NOW.minusSeconds(1), NOW.plusSeconds(300),
                (short) 0);

        assertThat(runStepRepository.findReapable(NOW, PageRequest.of(0, 1000))).extracting(RunStep::getId)
                .contains(overdue.getId(), leaseGone.getId())
                .doesNotContain(fresh.getId());
    }

    @Test
    void progressArrivingOutOfOrderNeverMovesTheLeaseDeliveryCountOrRunDeadlineBackwards() {
        RunStep step = claimed();
        Instant t0 = NOW.plusSeconds(10);
        transactions.recordPickup(step.getId(), (short) 1, t0.plusSeconds(100), t0.plusSeconds(220),
                t0.plusSeconds(400), (short) 2);

        assertThat(transactions.recordPickup(step.getId(), (short) 1, t0, t0.plusSeconds(120), t0.plusSeconds(300),
                (short) 1)).isTrue();
        assertThat(transactions.extendLease(step.getId(), (short) 1, t0.plusSeconds(5), t0.plusSeconds(125), null,
                (short) 1, t0.plusSeconds(305))).isTrue();

        RunStep reloaded = reload(step);
        assertThat(reloaded.getDeliveryCount()).isEqualTo((short) 2);
        assertThat(reloaded.getLeaseUntil()).isEqualTo(t0.plusSeconds(220));
        assertThat(reloaded.getRunDeadline()).isEqualTo(t0.plusSeconds(400));
    }

    @Test
    void theSchemaRejectsAProgressOutsideZeroToHundred() {
        RunStep step = claimed();

        assertThatThrownBy(() -> transactions.extendLease(step.getId(), (short) 1, NOW, NOW.plusSeconds(120),
                (short) 101, (short) 0, NOW.plusSeconds(300))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theStalledRunSweepTakesUntriedRunsFirstThenTheOneTriedLongestAgo() {
        UUID older = stalledRun(NOW.minusSeconds(3600));
        UUID newer = stalledRun(NOW);

        assertThat(sweepOrder(older, newer)).containsExactly(older, newer);

        workflowRunRepository.markSwept(List.of(older), NOW);
        assertThat(sweepOrder(older, newer)).containsExactly(newer, older);

        workflowRunRepository.markSwept(List.of(newer), NOW.plusSeconds(30));
        assertThat(sweepOrder(older, newer)).containsExactly(older, newer);
    }

    private UUID stalledRun(Instant startedAt) {
        Workflow workflow = workflowRepository.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "it-" + UUID.randomUUID(), false));
        WorkflowRun run = workflowRunRepository.saveAndFlush(
                new WorkflowRun(UUID.randomUUID(), workflow.getId(), UUID.randomUUID(), "in", startedAt));
        runStepRepository.saveAndFlush(new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "probe"));
        return run.getId();
    }

    /** The sweep's order restricted to these runs; other tests leave stalled runs in the same database. */
    private List<UUID> sweepOrder(UUID... runIds) {
        Set<UUID> wanted = Set.of(runIds);
        return workflowRunRepository.findStalledRunIds(PageRequest.of(0, 10_000)).stream()
                .filter(wanted::contains).toList();
    }

    @Test
    void theReaperScanCanUseThePartialRunningIndex() throws Exception {
        List<String> plan = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (ResultSet rows = statement.executeQuery("EXPLAIN SELECT * FROM run_step WHERE status = 'running' AND "
                    + "((picked_up_at IS NULL AND queue_deadline < now()) OR (picked_up_at IS NOT NULL "
                    + "AND (lease_until < now() OR run_deadline < now()))) LIMIT 100")) {
                while (rows.next()) {
                    plan.add(rows.getString(1));
                }
            } finally {
                statement.execute("RESET enable_seqscan");
            }
        }

        assertThat(String.join("\n", plan)).contains("idx_run_step_running");
    }
}
