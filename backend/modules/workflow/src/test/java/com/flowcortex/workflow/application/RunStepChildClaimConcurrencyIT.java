package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository.ChildRow;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The item statements of an iterate step against a real Postgres on the Flyway-migrated schema: the
 * claim of an item takes the run's row lock and then counts the running items inside its own
 * {@code UPDATE}, which is what keeps racing claims under the concurrency cap. Not wrapped in a test
 * transaction, so every transaction here really commits.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=filesystem:${flowcortex.migrations.dir}",
        "spring.flyway.postgresql.transactional-lock=false",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class RunStepChildClaimConcurrencyIT {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant QUEUE_DEADLINE = NOW.plusSeconds(1800);

    @Autowired
    private RunStepRepository runStepRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return newTransaction().execute(status -> work.get());
    }

    private WorkflowRun newRun() {
        Workflow workflow = workflowRepository.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "it-" + UUID.randomUUID(), false));
        return workflowRunRepository.saveAndFlush(
                new WorkflowRun(UUID.randomUUID(), workflow.getId(), UUID.randomUUID(), "in", NOW));
    }

    private WorkflowRun completedRun() {
        WorkflowRun run = newRun();
        run.markCompleted(NOW);
        return workflowRunRepository.saveAndFlush(run);
    }

    private RunStep runningParent(WorkflowRun run) {
        RunStep parent = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "iterate");
        parent.markRunning(NOW);
        return runStepRepository.saveAndFlush(parent);
    }

    private RunStep completedParent(WorkflowRun run) {
        RunStep parent = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "iterate");
        parent.markRunning(NOW);
        parent.markCompleted("in", "{\"total\":3}", NOW, "[]");
        return runStepRepository.saveAndFlush(parent);
    }

    private RunStep pendingChild(RunStep parent, int parentAttempt, int itemIndex) {
        return runStepRepository.saveAndFlush(RunStep.child(parent.getRunId(), parent.getStepOrder(), "echo",
                parent.getId(), (short) parentAttempt, (short) itemIndex, "item-" + itemIndex));
    }

    private RunStep runningChild(RunStep parent, int parentAttempt, int itemIndex) {
        RunStep child = RunStep.child(parent.getRunId(), parent.getStepOrder(), "echo", parent.getId(),
                (short) parentAttempt, (short) itemIndex, "item-" + itemIndex);
        child.markRunning(NOW);
        return runStepRepository.saveAndFlush(child);
    }

    private RunStep completedChild(RunStep parent, int parentAttempt, int itemIndex, String output) {
        RunStep child = RunStep.child(parent.getRunId(), parent.getStepOrder(), "echo", parent.getId(),
                (short) parentAttempt, (short) itemIndex, "item-" + itemIndex);
        child.markRunning(NOW);
        child.markCompleted("item-" + itemIndex, output, NOW, "[{\"name\":\"" + output + ".wav\"}]");
        return runStepRepository.saveAndFlush(child);
    }

    private RunStep failedChild(RunStep parent, int parentAttempt, int itemIndex) {
        RunStep child = RunStep.child(parent.getRunId(), parent.getStepOrder(), "echo", parent.getId(),
                (short) parentAttempt, (short) itemIndex, "item-" + itemIndex);
        child.markRunning(NOW);
        child.markError("item-" + itemIndex, "boom", "failed", NOW);
        return runStepRepository.saveAndFlush(child);
    }

    private RunStep reload(RunStep step) {
        return runStepRepository.findById(step.getId()).orElseThrow();
    }

    /** What a claim of the engine does: the run's row lock first, then the conditional update. */
    private int claimPending(RunStep parent, RunStep child, int limit) {
        return inTransaction(() -> {
            workflowRunRepository.lockById(parent.getRunId());
            return runStepRepository.claimPendingChild(child.getId(), parent.getId(), (short) 1, limit, NOW,
                    QUEUE_DEADLINE, child.getInput());
        });
    }

    private int claimRegenerated(RunStep parent, RunStep child, int limit) {
        return inTransaction(() -> {
            workflowRunRepository.lockById(parent.getRunId());
            return runStepRepository.claimRegeneratedChild(child.getId(), parent.getId(), (short) 1, limit, NOW,
                    QUEUE_DEADLINE, child.getInput());
        });
    }

    @Test
    void twoClaimsAtLimitOneTakeOneSlot() throws Exception {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        runningChild(parent, 1, 0);
        RunStep second = pendingChild(parent, 1, 1);
        RunStep third = pendingChild(parent, 1, 2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(claiming(parent, second, 2, ready, go));
            Future<Integer> other = pool.submit(claiming(parent, third, 2, ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int claimed = first.get(10, TimeUnit.SECONDS) + other.get(10, TimeUnit.SECONDS);

            assertThat(claimed).as("two claims at limit 2 with one child running take exactly one slot").isEqualTo(1);
            assertThat(runStepRepository.countRunningChildren(parent.getId(), (short) 1)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Integer> claiming(RunStep parent, RunStep child, int limit, CountDownLatch ready,
            CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await();
            return claimPending(parent, child, limit);
        };
    }

    @Test
    void childClaimRefusedWhenParentAttemptMoved() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        RunStep earlier = pendingChild(parent, 1, 0);

        inTransaction(() -> runStepRepository.failIfRunning(parent.getId(), run.getId(), (short) 1, "x", "stopped", NOW));
        assertThat(claimPending(parent, earlier, 5)).as("the parent is no longer running").isZero();

        inTransaction(() -> runStepRepository.reArm(parent.getId(), run.getId(), (short) 1, "in"));
        assertThat(inTransaction(() -> runStepRepository.claimPending(parent.getId(), NOW, QUEUE_DEADLINE, "in")))
                .isEqualTo(1);
        RunStep next = pendingChild(reload(parent), 2, 0);

        assertThat(claimPending(parent, earlier, 5)).as("the item belongs to the parent's earlier attempt").isZero();
        assertThat(reload(earlier).getStatus()).isEqualTo(RunStepStatus.PENDING);
        int claimedForCurrent = inTransaction(() -> {
            workflowRunRepository.lockById(run.getId());
            return runStepRepository.claimPendingChild(next.getId(), parent.getId(), (short) 2, 5, NOW,
                    QUEUE_DEADLINE, next.getInput());
        });
        assertThat(claimedForCurrent).isEqualTo(1);
    }

    @Test
    void regeneratedClaimOnlyInACompletedRun() {
        WorkflowRun completed = completedRun();
        RunStep parent = completedParent(completed);
        RunStep regenerated = pendingChild(parent, 1, 0);
        assertThat(claimPending(parent, regenerated, 5)).as("a running claim needs a running run").isZero();
        assertThat(claimRegenerated(parent, regenerated, 5)).isEqualTo(1);

        WorkflowRun running = newRun();
        RunStep runningIterate = completedParent(running);
        RunStep notYet = pendingChild(runningIterate, 1, 0);
        assertThat(claimRegenerated(runningIterate, notYet, 5)).as("the run is still running").isZero();

        WorkflowRun otherCompleted = completedRun();
        RunStep openParent = runningParent(otherCompleted);
        RunStep orphan = pendingChild(openParent, 1, 0);
        assertThat(claimRegenerated(openParent, orphan, 5)).as("the parent is not completed").isZero();
    }

    @Test
    void regeneratedClaimRespectsTheCap() {
        WorkflowRun run = completedRun();
        RunStep parent = completedParent(run);
        runningChild(parent, 1, 0);
        RunStep waiting = pendingChild(parent, 1, 1);

        assertThat(claimRegenerated(parent, waiting, 1)).isZero();
        assertThat(reload(waiting).getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(claimRegenerated(parent, waiting, 2)).isEqualTo(1);
    }

    @Test
    void reArmChildKeepsInputAndBumpsAttempt() {
        WorkflowRun run = completedRun();
        RunStep parent = completedParent(run);
        RunStep done = completedChild(parent, 1, 0, "out");
        RunStep failed = failedChild(parent, 1, 1);

        assertThat(inTransaction(() -> runStepRepository.reArmChild(done.getId(), (short) 1))).isEqualTo(1);
        assertThat(inTransaction(() -> runStepRepository.reArmChild(done.getId(), (short) 1)))
                .as("the second caller holds a stale attempt and also finds it pending").isZero();
        assertThat(inTransaction(() -> runStepRepository.reArmChild(failed.getId(), (short) 1))).isEqualTo(1);
        assertThat(inTransaction(() -> runStepRepository.reArmChild(parent.getId(), (short) 1)))
                .as("a top-level step is not an item").isZero();

        RunStep rearmed = reload(done);
        assertThat(rearmed.getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(rearmed.getAttempt()).isEqualTo((short) 2);
        assertThat(rearmed.getInput()).isEqualTo("item-0");
        assertThat(rearmed.getOutput()).isNull();
        assertThat(rearmed.getOutputs()).isNull();
        assertThat(rearmed.getStartedAt()).isNull();
        assertThat(rearmed.getFinishedAt()).isNull();
        RunStep rearmedFailure = reload(failed);
        assertThat(rearmedFailure.getErrorCode()).isNull();
        assertThat(rearmedFailure.getErrorMessage()).isNull();
        assertThat(rearmedFailure.getAttempt()).isEqualTo((short) 2);
    }

    @Test
    void childStatesReadTheCommittedStatusAfterATransition() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        RunStep child = runningChild(parent, 1, 0);

        List<ChildRow> states = newTransaction().execute(status -> {
            assertThat(runStepRepository.findById(child.getId()).orElseThrow().getStatus())
                    .isEqualTo(RunStepStatus.RUNNING);
            inTransaction(() -> runStepRepository.failIfRunning(child.getId(), run.getId(), (short) 1,
                    "INSUFFICIENT_CREDITS", "no credits", NOW));
            assertThat(runStepRepository.findById(child.getId()).orElseThrow().getStatus())
                    .as("the entity this transaction loaded still shows its earlier status")
                    .isEqualTo(RunStepStatus.RUNNING);
            return runStepRepository.findChildStates(parent.getId(), (short) 1);
        });

        assertThat(states).hasSize(1);
        assertThat(states.get(0).getStatus()).isEqualTo(RunStepStatus.ERROR);
        assertThat(states.get(0).getErrorCode()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(states.get(0).getErrorMessage()).isEqualTo("no credits");
    }

    @Test
    void childStatesAreInItemOrderWithTheirOutputs() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        pendingChild(parent, 1, 2);
        completedChild(parent, 1, 0, "zero");
        pendingChild(parent, 2, 0);

        List<ChildRow> states = runStepRepository.findChildStates(parent.getId(), (short) 1);

        assertThat(states).extracting(ChildRow::getItemIndex).containsExactly((short) 0, (short) 2);
        assertThat(states.get(0).getOutput()).isEqualTo("zero");
        assertThat(states.get(0).getOutputs()).contains("zero.wav");
        assertThat(states.get(1).getStatus()).isEqualTo(RunStepStatus.PENDING);
    }

    @Test
    void latestCompletedChildrenPicksTheHighestEarlierAttempt() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        completedChild(parent, 1, 0, "a1");
        completedChild(parent, 2, 0, "a2");
        completedChild(parent, 3, 0, "a3");
        completedChild(parent, 1, 1, "b1");
        failedChild(parent, 2, 1);
        failedChild(parent, 1, 2);

        List<RunStep> beforeThree = runStepRepository.findLatestCompletedChildren(parent.getId(), (short) 3);
        assertThat(beforeThree).extracting(RunStep::getOutput).containsExactly("a2", "b1");
        assertThat(beforeThree).extracting(RunStep::getItemIndex).containsExactly((short) 0, (short) 1);

        assertThat(runStepRepository.findLatestCompletedChildren(parent.getId(), (short) 2))
                .extracting(RunStep::getOutput).containsExactly("a1", "b1");
        assertThat(runStepRepository.findLatestCompletedChildren(parent.getId(), (short) 1)).isEmpty();
    }

    @Test
    void currentChildrenAreThoseOfTheParentsCurrentAttempt() {
        WorkflowRun run = newRun();
        RunStep retried = runningParent(run);
        pendingChild(retried, 1, 0);
        pendingChild(retried, 1, 1);
        inTransaction(() -> runStepRepository.failIfRunning(retried.getId(), run.getId(), (short) 1, "x", "failed", NOW));
        inTransaction(() -> runStepRepository.reArm(retried.getId(), run.getId(), (short) 1, "in"));
        RunStep currentSecond = pendingChild(retried, 2, 1);
        RunStep currentFirst = pendingChild(retried, 2, 0);
        RunStep later = runStepRepository.saveAndFlush(new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "iterate"));
        RunStep laterItem = pendingChild(later, 1, 0);

        List<RunStep> current = runStepRepository.findCurrentChildren(run.getId());

        assertThat(current).extracting(RunStep::getId)
                .as("by parent order and item index, none of the parent's earlier attempt")
                .containsExactly(currentFirst.getId(), currentSecond.getId(), laterItem.getId());
    }

    @Test
    void findByRunIdOrderByStepOrderAscOmitsChildren() {
        WorkflowRun run = newRun();
        RunStep first = runStepRepository.saveAndFlush(new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo"));
        RunStep parent = runStepRepository.saveAndFlush(
                new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "iterate"));
        pendingChild(parent, 1, 0);
        pendingChild(parent, 1, 1);

        assertThat(runStepRepository.findByRunIdOrderByStepOrderAsc(run.getId()))
                .extracting(RunStep::getId).containsExactly(first.getId(), parent.getId());
        assertThat(runStepRepository.countTopLevelNotCompleted(run.getId())).isEqualTo(2);
    }

    @Test
    void activeChildrenAreTheRunningAndThePendingOnes() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        runningChild(parent, 1, 0);
        pendingChild(parent, 1, 1);
        completedChild(parent, 1, 2, "x");
        failedChild(parent, 1, 3);

        assertThat(runStepRepository.countActiveChildren(parent.getId(), (short) 1)).isEqualTo(2);
        assertThat(runStepRepository.countRunningChildren(parent.getId(), (short) 1)).isEqualTo(1);
        assertThat(runStepRepository.countActiveChildren(parent.getId(), (short) 2)).isZero();
    }

    @Test
    void skipPendingChildrenSkipsOnlyThePendingOnes() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        RunStep running = runningChild(parent, 1, 0);
        RunStep pending = pendingChild(parent, 1, 1);
        RunStep otherParent = runningParent(newRun());
        RunStep otherChild = pendingChild(otherParent, 1, 0);

        assertThat(inTransaction(() -> runStepRepository.skipPendingChildren(parent.getId()))).isEqualTo(1);

        assertThat(reload(pending).getStatus()).isEqualTo(RunStepStatus.SKIPPED);
        assertThat(reload(running).getStatus()).isEqualTo(RunStepStatus.RUNNING);
        assertThat(reload(otherChild).getStatus()).isEqualTo(RunStepStatus.PENDING);
    }

    @Test
    void replaceCompletedOutputNeedsACompletedParent() {
        WorkflowRun run = completedRun();
        RunStep done = completedParent(run);
        RunStep open = runningParent(newRun());

        assertThat(inTransaction(() -> runStepRepository.replaceCompletedOutput(done.getId(), "new", "[1]")))
                .isEqualTo(1);
        assertThat(inTransaction(() -> runStepRepository.replaceCompletedOutput(open.getId(), "new", "[1]")))
                .isZero();

        RunStep replaced = reload(done);
        assertThat(replaced.getOutput()).isEqualTo("new");
        assertThat(replaced.getOutputs()).isEqualTo("[1]");
        assertThat(replaced.getStatus()).isEqualTo(RunStepStatus.COMPLETED);
    }

    @Test
    void idleIterateParentsHaveNoRunningChildInTheirCurrentAttempt() {
        WorkflowRun idleRun = newRun();
        RunStep idle = runningParent(idleRun);
        completedChild(idle, 1, 0, "x");

        WorkflowRun busyRun = newRun();
        RunStep busy = runningParent(busyRun);
        runningChild(busy, 1, 0);

        assertThat(runStepRepository.findIdleIterateParents(idleRun.getId()))
                .extracting(RunStepRepository.IdleParent::getId, RunStepRepository.IdleParent::getAttempt)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(idle.getId(), idle.getAttempt()));
        assertThat(runStepRepository.findIdleIterateParents(busyRun.getId())).isEmpty();
    }

    @Test
    void lineageNamesTheParentOfAnItemAndNothingForATopLevelStep() {
        RunStep parent = runningParent(newRun());
        RunStep child = pendingChild(parent, 1, 3);

        RunStepRepository.Lineage item = runStepRepository.findLineageById(child.getId()).orElseThrow();
        RunStepRepository.Lineage top = runStepRepository.findLineageById(parent.getId()).orElseThrow();

        assertThat(item.getParentStepId()).isEqualTo(parent.getId());
        assertThat(item.getParentAttempt()).isEqualTo((short) 1);
        assertThat(item.getItemIndex()).isEqualTo((short) 3);
        assertThat(item.getType()).isEqualTo("echo");
        assertThat(top.getParentStepId()).isNull();
        assertThat(top.getType()).isEqualTo("iterate");
    }

    @Test
    void resultReadsTheStatusAttemptAndOutputsAsCommitted() {
        RunStep parent = completedParent(newRun());

        RunStepRepository.StepResult result = runStepRepository.findResultById(parent.getId()).orElseThrow();

        assertThat(result.getStatus()).isEqualTo(RunStepStatus.COMPLETED);
        assertThat(result.getAttempt()).isEqualTo((short) 1);
        assertThat(result.getOutput()).isEqualTo("{\"total\":3}");
        assertThat(result.getOutputs()).isEqualTo("[]");
    }

    @Test
    void stalledQueryReturnsARunWhoseIterateHasNoRunningChild() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        completedChild(parent, 1, 0, "x");
        completedChild(parent, 1, 1, "y");

        assertThat(runStepRepository.findByRunIdOrderByStepOrderAsc(run.getId())).hasSize(1);
        assertThat(stalled(run.getId())).isTrue();
    }

    @Test
    void stalledQuerySkipsARunWhoseIterateHasARunningChild() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        completedChild(parent, 1, 0, "x");
        runningChild(parent, 1, 1);

        assertThat(stalled(run.getId())).isFalse();
    }

    @Test
    void stalledQuerySkipsARunWithAnotherRunningStepBesideAnIdleIterate() {
        WorkflowRun run = newRun();
        RunStep parent = runningParent(run);
        completedChild(parent, 1, 0, "x");
        RunStep other = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "echo");
        other.markRunning(NOW);
        runStepRepository.saveAndFlush(other);

        assertThat(stalled(run.getId())).isFalse();
    }

    /** The sweep's answer restricted to this run; other tests leave stalled runs in the same database. */
    private boolean stalled(UUID runId) {
        return workflowRunRepository.findStalledRunIds(PageRequest.of(0, 10_000)).contains(runId);
    }
}
