package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.RunCancellation;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The batch dispatcher on the real app: rows start a few at a time, in order, under the batch, member and cluster
 * caps, and a batch finishes by itself. Approval workflows hold a run live so a slot stays taken. The dispatcher
 * is on for this class only (every other context keeps it off, since the contexts share one database); the
 * test also runs the job body itself while it waits, so a slow cluster runner shared with other contexts never
 * decides the outcome. Every test starts by closing the batches earlier tests left open, which also frees the
 * cluster cap they held.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.cancel.enabled=true",
        "flowcortex.workflow.batch.dispatch-enabled=true",
        "flowcortex.workflow.batch.dispatch-interval=PT0.2S",
        "flowcortex.jobs.runner.enabled=true",
        "flowcortex.jobs.poll-interval=100ms"})
@AutoConfigureTestRestTemplate
@DirtiesContext
class BatchDispatchIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final Duration QUIET = Duration.ofMillis(1500);
    private static final Pattern PASS_MS = Pattern.compile("pass_ms=(\\d+)");
    private static final String DISPATCHER_LOG = "com.flowcortex.workflow.application.BatchDispatcher";
    private static final List<ILoggingEvent> PASSES = new CopyOnWriteArrayList<>();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private List<ClusterJob> clusterJobs;

    private ListAppender<ILoggingEvent> passLog;

    record ReviewTaskView(UUID id, String status) {
    }

    record CreatedBatch(UUID id) {
    }

    @BeforeEach
    void closeOpenBatchesAndListenToPasses() {
        jdbc.update("UPDATE batch_run SET status = CASE WHEN status IN ('running', 'paused') THEN 'cancelled' "
                + "ELSE status END, finished_at = now(), lease_token = NULL, lease_until = NULL "
                + "WHERE finished_at IS NULL");
        jdbc.update("UPDATE batch_run_row SET status = 'cancelled', finished_at = now() "
                + "WHERE status IN ('pending', 'starting', 'running')");
        passLog = new ListAppender<>();
        passLog.start();
        ((Logger) LoggerFactory.getLogger(DISPATCHER_LOG)).addAppender(passLog);
    }

    @AfterEach
    void restoreSwitches() {
        ((Logger) LoggerFactory.getLogger(DISPATCHER_LOG)).detachAppender(passLog);
        PASSES.addAll(passLog.list);
        setWorkflowEnabled(true);
        setCancellationEnabled(true);
    }

    @AfterAll
    static void reportTheLongestPass() {
        int longest = PASSES.stream().map(event -> PASS_MS.matcher(event.getFormattedMessage()))
                .filter(Matcher::find).mapToInt(match -> Integer.parseInt(match.group(1))).max().orElse(-1);
        System.out.println("batch.dispatch passes=" + PASSES.size() + " largest pass_ms=" + longest);
    }

    @Test
    void concurrencyTwoStartsRowsInOrderAndNeverMoreThanTwoLive() {
        UserView member = member("order", 1000);
        String token = loginAs(member);
        UUID batch = create(token, approvalWorkflow(member), List.of("r0", "r1", "r2", "r3", "r4"), 2);

        try (LiveSampler sampler = new LiveSampler(member)) {
            until(() -> rowStatus(batch, 0).equals("running") && rowStatus(batch, 1).equals("running"));
            quietFor(QUIET);
            assertThat(rowStatuses(batch)).containsExactly("running", "running", "pending", "pending", "pending");

            for (int index = 0; index < 5; index++) {
                UUID run = awaitRun(batch, index);
                awaitRunStatus(run, "waiting_approval");
                decide(token, run, "approve");
                int finished = index;
                until(() -> rowStatus(batch, finished).equals("completed"));
            }
            until(() -> batchStatus(batch).equals("completed") && finishedAt(batch) != null);

            assertThat(sampler.maxLive()).as("live rows seen at any moment").isLessThanOrEqualTo(2);
        }
        assertThat(jdbc.queryForList("SELECT started_at FROM batch_run_row WHERE batch_id = ? ORDER BY row_index",
                Timestamp.class, batch)).isSorted();
        assertThat(jdbc.queryForList("SELECT request_key FROM workflow_run WHERE owner_id = ? ORDER BY request_key",
                String.class, member.id())).containsExactly("batch:" + batch + ":0", "batch:" + batch + ":1",
                "batch:" + batch + ":2", "batch:" + batch + ":3", "batch:" + batch + ":4");
    }

    @Test
    void aWaitingApprovalRunHoldsItsSlot() {
        UserView member = member("holds-slot", 1000);
        String token = loginAs(member);
        UUID batch = create(token, approvalWorkflow(member), List.of("a", "b"), 1);

        UUID first = awaitRun(batch, 0);
        awaitRunStatus(first, "waiting_approval");
        quietFor(QUIET);
        assertThat(rowStatuses(batch)).containsExactly("running", "pending");

        decide(token, first, "approve");
        until(() -> rowStatus(batch, 1).equals("running"));
        assertThat(rowStatus(batch, 0)).isEqualTo("completed");
    }

    @Test
    void aRowStartedTwiceKeepsOneRun() {
        UserView member = member("twice", 1000);
        WorkflowView workflow = echoWorkflow(member);
        UUID batch = UUID.randomUUID();
        UUID run = startRun(member, workflow, "pre-created", "batch:" + batch + ":0");
        setWorkflowEnabled(false);
        seedBatch(batch, member, workflow, "running", 1, new RowSeed("starting", null, Instant.now().minusSeconds(600)));

        until(() -> batchStatus(batch).equals("completed") && finishedAt(batch) != null);

        assertThat(rowRunId(batch, 0)).isEqualTo(run);
        assertThat(rowStatus(batch, 0)).isEqualTo("completed");
        assertThat(count("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", workflow.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pause_reason FROM batch_run WHERE id = ?", String.class, batch))
                .as("a start was never attempted, so nothing was refused").isNull();
    }

    @Test
    void anAbandonedStartingRowInACancelledBatchWithARunIsRecordedAndStopped() {
        UserView member = member("abandoned-run", 1000);
        WorkflowView workflow = approvalWorkflow(member);
        UUID batch = UUID.randomUUID();
        UUID run = startRun(member, workflow, "pre-created", "batch:" + batch + ":0");
        awaitRunStatus(run, "waiting_approval");
        seedBatch(batch, member, workflow, "cancelled", 1, new RowSeed("starting", null, Instant.now().minusSeconds(600)));

        until(() -> finishedAt(batch) != null);

        assertThat(rowRunId(batch, 0)).isEqualTo(run);
        assertThat(rowStatus(batch, 0)).isEqualTo("cancelled");
        assertThat(runStatus(run)).isEqualTo("cancelled");
        assertThat(batchStatus(batch)).isEqualTo("cancelled");
    }

    @Test
    void anAbandonedStartingRowInACancelledBatchWithoutARunIsCancelled() {
        UserView member = member("abandoned-none", 1000);
        WorkflowView workflow = echoWorkflow(member);
        UUID batch = UUID.randomUUID();
        seedBatch(batch, member, workflow, "cancelled", 1, new RowSeed("starting", null, Instant.now().minusSeconds(600)));

        until(() -> finishedAt(batch) != null);

        assertThat(rowStatus(batch, 0)).isEqualTo("cancelled");
        assertThat(rowRunId(batch, 0)).isNull();
        assertThat(count("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", workflow.id())).isZero();
    }

    @Test
    void aRecentStartingRowIsLeftToItsClaimer() {
        UserView member = member("recent-claim", 1000);
        WorkflowView workflow = echoWorkflow(member);
        UUID batch = UUID.randomUUID();
        seedBatch(batch, member, workflow, "running", 1, new RowSeed("starting", null, Instant.now()),
                new RowSeed("pending", null, null));

        quietFor(QUIET);

        assertThat(rowStatuses(batch)).as("a claim in flight is not touched and still holds the only slot")
                .containsExactly("starting", "pending");
        assertThat(count("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", workflow.id())).isZero();
    }

    @Test
    void aSecondDispatcherWithoutTheLeaseStartsNothing() {
        UserView member = member("no-lease", 1000);
        WorkflowView workflow = echoWorkflow(member);
        UUID batch = UUID.randomUUID();
        seedBatch(batch, member, workflow, "running", 1, new RowSeed("pending", null, null));
        jdbc.update("UPDATE batch_run SET lease_token = ?, lease_until = now() + interval '10 minutes' WHERE id = ?",
                UUID.randomUUID(), batch);

        quietFor(QUIET);
        assertThat(rowStatus(batch, 0)).isEqualTo("pending");
        assertThat(count("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", workflow.id())).isZero();

        jdbc.update("UPDATE batch_run SET lease_until = now() - interval '1 second' WHERE id = ?", batch);
        until(() -> batchStatus(batch).equals("completed"));
        assertThat(rowStatus(batch, 0)).isEqualTo("completed");
    }

    @Test
    void batchFinishesCompletedWithErrorsWhenARunErrors() {
        UserView member = member("with-errors", 1000);
        String token = loginAs(member);
        UUID batch = create(token, approvalWorkflow(member), List.of("good", "bad"), 2);
        UUID good = awaitRun(batch, 0);
        UUID bad = awaitRun(batch, 1);
        awaitRunStatus(good, "waiting_approval");
        awaitRunStatus(bad, "waiting_approval");

        decide(token, bad, "reject");
        decide(token, good, "approve");

        until(() -> batchStatus(batch).equals("completed_with_errors") && finishedAt(batch) != null);
        assertThat(rowStatuses(batch)).containsExactly("completed", "failed");
        assertThat(jdbc.queryForObject("SELECT error_code FROM batch_run_row WHERE batch_id = ? AND row_index = 1",
                String.class, batch)).isEqualTo("runError");
        assertThat(rowRunId(batch, 1)).isEqualTo(bad);
    }

    @Test
    void insufficientCreditsPausesTheBatchAndResumeStartsTheRow() {
        UserView member = member("no-credits", 0);
        String token = loginAs(member);
        WorkflowView workflow = echoWorkflow(member);
        UUID batch = UUID.randomUUID();
        seedBatch(batch, member, workflow, "running", 1, new RowSeed("pending", null, null));

        until(() -> batchStatus(batch).equals("paused"));
        assertThat(pauseReason(batch)).isEqualTo("insufficientCredits");
        assertThat(rowStatus(batch, 0)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT start_failures FROM batch_run_row WHERE batch_id = ?", Integer.class,
                batch)).as("a refusal for credits is not a start failure").isZero();

        jdbc.update("UPDATE credit_wallet SET balance = 100 WHERE user_id = ?", member.id());
        assertThat(post("/api/v1/batches/" + batch + "/resume", token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        until(() -> batchStatus(batch).equals("completed"));
        assertThat(rowStatus(batch, 0)).isEqualTo("completed");
        assertThat(pauseReason(batch)).isNull();
    }

    @Test
    void workflowKillSwitchPausesAsUnavailableAndResumesWhenItIsBack() {
        UserView member = member("kill-switch", 1000);
        WorkflowView workflow = echoWorkflow(member);
        UUID batch = UUID.randomUUID();
        setWorkflowEnabled(false);
        seedBatch(batch, member, workflow, "running", 1, new RowSeed("pending", null, null));

        until(() -> batchStatus(batch).equals("paused"));
        assertThat(pauseReason(batch)).isEqualTo("unavailable");
        assertThat(rowStatus(batch, 0)).isEqualTo("pending");
        quietFor(QUIET);
        assertThat(batchStatus(batch)).as("still unavailable, so it stays paused").isEqualTo("paused");

        setWorkflowEnabled(true);

        until(() -> batchStatus(batch).equals("completed"));
        assertThat(rowStatus(batch, 0)).isEqualTo("completed");
        assertThat(pauseReason(batch)).isNull();
    }

    @Test
    void memberAndClusterCapsHoldAcrossTwoMembers() {
        UserView first = member("cap-first", 1000);
        UserView second = member("cap-second", 1000);
        List<String> inputs = List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        UUID firstBatch = create(loginAs(first), approvalWorkflow(first), inputs, 10);
        UUID secondBatch = create(loginAs(second), approvalWorkflow(second), inputs, 10);

        try (LiveSampler sampler = new LiveSampler(first, second)) {
            until(() -> live(firstBatch) + live(secondBatch) == 8);
            quietFor(QUIET);

            assertThat(live(firstBatch) + live(secondBatch)).as("cluster cap").isEqualTo(8);
            assertThat(live(firstBatch)).as("first member").isBetween(3L, 5L);
            assertThat(live(secondBatch)).as("second member").isBetween(3L, 5L);
            assertThat(sampler.maxLive()).as("any member at any moment").isLessThanOrEqualTo(5);
            assertThat(sampler.maxTotal()).as("the cluster at any moment").isLessThanOrEqualTo(8);
        }
    }

    @Test
    void aDispatchPassDoesNotRunOnTheSchedulerThread() {
        UserView member = member("threads", 1000);
        UUID batch = create(loginAs(member), echoWorkflow(member), List.of("a", "b", "c"), 3);

        until(() -> batchStatus(batch).equals("completed"));

        List<ILoggingEvent> mine = passLog.list.stream()
                .filter(event -> event.getFormattedMessage().contains("batch.dispatch")
                        && event.getFormattedMessage().contains(batch.toString()))
                .toList();
        assertThat(mine).isNotEmpty();
        assertThat(mine).extracting(ILoggingEvent::getThreadName).allMatch(name -> name.startsWith("batch-dispatch-"));
        assertThat(mine).allSatisfy(event -> assertThat(event.getFormattedMessage()).containsPattern("pass_ms=\\d+"));
        assertThat(batchJob().interval()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void workflowMadePrivateCancelsTheBatch() {
        UserView author = member("author", 1000);
        UserView borrower = member("borrower", 1000);
        WorkflowView workflow = newWorkflow(author.id(), true, new NewWorkflowStep("only", "echo", null));
        jdbc.update("UPDATE workflow SET visibility = 'private', is_shared = false WHERE id = ?", workflow.id());
        UUID batch = UUID.randomUUID();
        seedBatch(batch, borrower, workflow, "running", 1, new RowSeed("pending", null, null),
                new RowSeed("pending", null, null));

        until(() -> finishedAt(batch) != null);

        assertThat(batchStatus(batch)).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM batch_run WHERE id = ?", String.class, batch))
                .isEqualTo("workflowUnavailable");
        assertThat(rowStatuses(batch)).containsExactly("cancelled", "cancelled");
        assertThat(count("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", workflow.id())).isZero();
    }

    @Test
    void suspendedOwnerCancelsBeforeAnyStart() {
        UserView member = member("suspended", 1000);
        WorkflowView workflow = echoWorkflow(member);
        UUID batch = UUID.randomUUID();
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", member.id());
        seedBatch(batch, member, workflow, "running", 2, new RowSeed("pending", null, null),
                new RowSeed("pending", null, null));

        until(() -> finishedAt(batch) != null);

        assertThat(batchStatus(batch)).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT cancel_reason FROM batch_run WHERE id = ?", String.class, batch))
                .isEqualTo("ownerInactive");
        assertThat(rowStatuses(batch)).containsExactly("cancelled", "cancelled");
        assertThat(count("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", workflow.id())).isZero();
    }

    @Test
    void cancelWithRunCancellationOnStopsTheLiveRun() {
        UserView member = member("cancel-on", 1000);
        String token = loginAs(member);
        UUID batch = create(token, approvalWorkflow(member), List.of("a", "b", "c"), 1);
        UUID run = awaitRun(batch, 0);
        awaitRunStatus(run, "waiting_approval");

        assertThat(post("/api/v1/batches/" + batch + "/cancel", token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        until(() -> finishedAt(batch) != null);
        assertThat(runStatus(run)).isEqualTo("cancelled");
        assertThat(rowStatuses(batch)).containsExactly("cancelled", "cancelled", "cancelled");
        assertThat(batchStatus(batch)).isEqualTo("cancelled");
    }

    @Test
    void aRowClaimedBeforeCancelIsStoppedByTheNextDispatch() {
        UserView member = member("claimed-first", 1000);
        WorkflowView workflow = approvalWorkflow(member);
        UUID batch = UUID.randomUUID();
        UUID run = startRun(member, workflow, "in-flight", "batch:" + batch + ":0");
        awaitRunStatus(run, "waiting_approval");
        seedBatch(batch, member, workflow, "cancelled", 1, new RowSeed("running", run, Instant.now()));

        until(() -> finishedAt(batch) != null);

        assertThat(runStatus(run)).isEqualTo("cancelled");
        assertThat(rowStatus(batch, 0)).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT stop_requested FROM batch_run_row WHERE batch_id = ?", Boolean.class,
                batch)).isTrue();
    }

    @Test
    void cancelWithRunCancellationOffLetsTheLiveRunFinish() {
        setCancellationEnabled(false);
        UserView member = member("cancel-off", 1000);
        String token = loginAs(member);
        UUID batch = create(token, approvalWorkflow(member), List.of("a", "b"), 1);
        UUID run = awaitRun(batch, 0);
        awaitRunStatus(run, "waiting_approval");

        ResponseEntity<String> cancelled = post("/api/v1/batches/" + batch + "/cancel", token, null, String.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);

        until(() -> jdbc.queryForObject("SELECT stop_requested FROM batch_run_row WHERE batch_id = ? AND row_index = 0",
                Boolean.class, batch));
        quietFor(QUIET);
        assertThat(runStatus(run)).as("the run is not stopped").isEqualTo("waiting_approval");
        assertThat(rowStatuses(batch)).containsExactly("running", "cancelled");
        assertThat(finishedAt(batch)).as("a live row keeps the batch open").isNull();

        decide(token, run, "approve");

        until(() -> finishedAt(batch) != null);
        assertThat(rowStatuses(batch)).containsExactly("completed", "cancelled");
        assertThat(batchStatus(batch)).isEqualTo("cancelled");
    }

    @Test
    void aPendingRowCannotBeStrandedInACancelledBatch() {
        UserView member = member("stranded", 1000);
        WorkflowView workflow = echoWorkflow(member);
        UUID batch = UUID.randomUUID();
        String function = "it_cancel_after_claim_" + batch.toString().replace("-", "");
        setWorkflowEnabled(false);
        seedBatch(batch, member, workflow, "paused", 1, new RowSeed("pending", null, null),
                new RowSeed("pending", null, null));
        jdbc.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "UPDATE batch_run SET status = 'cancelled', cancel_reason = 'member' "
                + "WHERE id = NEW.batch_id AND status = 'running'; RETURN NEW; END $$");
        try {
            jdbc.execute("CREATE TRIGGER " + function + " AFTER UPDATE OF status ON batch_run_row FOR EACH ROW "
                    + "WHEN (NEW.status = 'starting' AND OLD.status = 'pending' AND NEW.batch_id = '" + batch + "') "
                    + "EXECUTE FUNCTION " + function + "()");
            // Resumed by a member while the switch is off: the claim commits, a member cancel lands right after it,
            // and the refused start (503) then finds a cancelled batch.
            jdbc.update("UPDATE batch_run SET status = 'running', pause_reason = NULL WHERE id = ?", batch);

            until(() -> finishedAt(batch) != null);

            assertThat(batchStatus(batch)).isEqualTo("cancelled");
            assertThat(rowStatuses(batch)).containsExactly("cancelled", "cancelled");
            assertThat(count("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", workflow.id())).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + function + " ON batch_run_row");
            jdbc.execute("DROP FUNCTION IF EXISTS " + function + "()");
        }
    }

    // --- support -------------------------------------------------------------------------------------------

    private UserView member(String prefix, long balance) {
        UserView member = newUser(prefix);
        await().atMost(Duration.ofSeconds(10)).until(() -> count(
                "SELECT count(*) FROM credit_wallet WHERE user_id = ?", member.id()) == 1);
        jdbc.update("UPDATE credit_wallet SET balance = ? WHERE user_id = ?", balance, member.id());
        return member;
    }

    private WorkflowView echoWorkflow(UserView owner) {
        return newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
    }

    /** Echo, then a checkpoint that waits for the member, then echo: a run that holds its slot until decided. */
    private WorkflowView approvalWorkflow(UserView owner) {
        return newWorkflow(owner.id(), false, new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("review", "APPROVAL_CHECKPOINT", null), new NewWorkflowStep("publish", "echo", null));
    }

    private UUID create(String token, WorkflowView workflow, List<String> rows, int concurrency) {
        Map<String, Object> body = new HashMap<>();
        body.put("rows", rows);
        body.put("concurrency", concurrency);
        ResponseEntity<CreatedBatch> created = post("/api/v1/workflows/" + workflow.id() + "/batches", token, body,
                CreatedBatch.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().id();
    }

    private UUID startRun(UserView owner, WorkflowView workflow, String input, String requestKey) {
        return workflowRunService.startRun(owner.id(), workflow.id(), input, null, null, null, false, requestKey).id();
    }

    record RowSeed(String status, UUID runId, Instant claimedAt) {
    }

    /** The batch and its rows in one transaction, so a dispatch pass never sees the batch without its rows. */
    private void seedBatch(UUID id, UserView owner, WorkflowView workflow, String status, int concurrency,
            RowSeed... rows) {
        transactions.executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO batch_run (id, owner_id, workflow_id, concurrency, acknowledge_cost, status, "
                    + "total_rows, created_at) VALUES (?, ?, ?, ?, false, ?, ?, now())",
                    id, owner.id(), workflow.id(), concurrency, status, rows.length);
            for (int index = 0; index < rows.length; index++) {
                RowSeed seed = rows[index];
                jdbc.update("INSERT INTO batch_run_row (id, batch_id, row_index, input, status, run_id, claimed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), id, index, "row-" + index, seed.status(),
                        seed.runId(), seed.claimedAt() == null ? null : Timestamp.from(seed.claimedAt()));
            }
        });
    }

    private void decide(String token, UUID runId, String decision) {
        ResponseEntity<ReviewTaskView[]> tasks = get("/api/v1/runs/" + runId + "/review-tasks", token,
                ReviewTaskView[].class);
        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReviewTaskView pending = List.of(tasks.getBody()).stream().filter(task -> "pending".equals(task.status()))
                .findFirst().orElseThrow(() -> new AssertionError("no pending review task for run " + runId));
        assertThat(post("/api/v1/review-tasks/" + pending.id() + "/" + decision, token, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Waits for {@code condition}, running the job body each time round so the outcome never hangs on a shared runner. */
    private void until(BooleanSupplier condition) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100)).until(() -> {
            batchJob().run();
            return condition.getAsBoolean();
        });
    }

    private void quietFor(Duration span) {
        Instant end = Instant.now().plus(span);
        while (Instant.now().isBefore(end)) {
            batchJob().run();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }

    private ClusterJob batchJob() {
        return clusterJobs.stream().filter(job -> "workflow.batch-dispatch".equals(job.name())).findFirst()
                .orElseThrow(() -> new AssertionError("workflow.batch-dispatch is not registered"));
    }

    private UUID awaitRun(UUID batch, int index) {
        until(() -> rowRunId(batch, index) != null);
        return rowRunId(batch, index);
    }

    private void awaitRunStatus(UUID run, String status) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> status.equals(runStatus(run)));
    }

    private void setWorkflowEnabled(boolean enabled) {
        ReflectionTestUtils.setField(context.getBean("runStartPreflight"), "workflowEnabled", enabled);
    }

    private void setCancellationEnabled(boolean enabled) {
        Object target = AopTestUtils.getUltimateTargetObject(context.getBean(RunCancellation.class));
        ReflectionTestUtils.setField(target, "enabled", enabled);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long live(UUID batch) {
        return count("SELECT count(*) FROM batch_run_row WHERE batch_id = ? AND status IN ('starting', 'running')",
                batch);
    }

    private String batchStatus(UUID batch) {
        return jdbc.queryForObject("SELECT status FROM batch_run WHERE id = ?", String.class, batch);
    }

    private String pauseReason(UUID batch) {
        return jdbc.queryForObject("SELECT pause_reason FROM batch_run WHERE id = ?", String.class, batch);
    }

    private Timestamp finishedAt(UUID batch) {
        return jdbc.queryForObject("SELECT finished_at FROM batch_run WHERE id = ?", Timestamp.class, batch);
    }

    private String rowStatus(UUID batch, int index) {
        return jdbc.queryForObject("SELECT status FROM batch_run_row WHERE batch_id = ? AND row_index = ?",
                String.class, batch, index);
    }

    private List<String> rowStatuses(UUID batch) {
        return jdbc.queryForList("SELECT status FROM batch_run_row WHERE batch_id = ? ORDER BY row_index",
                String.class, batch);
    }

    private UUID rowRunId(UUID batch, int index) {
        return jdbc.queryForObject("SELECT run_id FROM batch_run_row WHERE batch_id = ? AND row_index = ?",
                UUID.class, batch, index);
    }

    private String runStatus(UUID run) {
        return jdbc.queryForObject("SELECT status FROM workflow_run WHERE id = ?", String.class, run);
    }

    /** Samples the live rows of some members every few milliseconds and keeps the highest counts seen. */
    private final class LiveSampler implements AutoCloseable {

        private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        private final AtomicInteger maxLive = new AtomicInteger();
        private final AtomicInteger maxTotal = new AtomicInteger();

        LiveSampler(UserView... owners) {
            List<UUID> ids = new ArrayList<>();
            for (UserView owner : owners) {
                ids.add(owner.id());
            }
            sampler.scheduleWithFixedDelay(() -> {
                int total = 0;
                for (UUID owner : ids) {
                    int live = jdbc.queryForObject("SELECT count(*) FROM batch_run_row r JOIN batch_run b "
                            + "ON b.id = r.batch_id WHERE b.owner_id = ? AND r.status IN ('starting', 'running')",
                            Integer.class, owner);
                    maxLive.accumulateAndGet(live, Math::max);
                    total += live;
                }
                int seen = total;
                maxTotal.accumulateAndGet(seen, Math::max);
            }, 0, 15, TimeUnit.MILLISECONDS);
        }

        int maxLive() {
            return maxLive.get();
        }

        int maxTotal() {
            return maxTotal.get();
        }

        @Override
        public void close() {
            sampler.shutdownNow();
        }
    }
}
