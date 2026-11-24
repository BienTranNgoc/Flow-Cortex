package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.RunResumption;
import com.flowcortex.workflow.application.StepClosingTestConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The iterate step through the real engine, with credits and the cancel switch on. This is the first
 * context that loads with the step registered, so start-up also proves the bean wiring and the price
 * coverage. The cluster runner stays off and the reaper is driven by hand, so a seeded run is never
 * advanced by a sweep the test did not ask for. Rows are found by run and step id, because the database
 * is shared with other ITs. Dubbing is on against a local stand-in for the provider, so a {@code translate}
 * child runs the whole claim, charge and refund path.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.cancel.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000",
        "flowcortex.dub.enabled=true",
        "flowcortex.llm.gemini.api-key=fake-key-iterate",
        "flowcortex.llm.gemini.slots.dub-translate.attempts=1",
        "flowcortex.llm.gemini.slots.dub-translate.retry-waits="})
@AutoConfigureTestRestTemplate
@Import(StepClosingTestConfig.class)
class IterateStepIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String ECHO = "{\"child\":{\"type\":\"echo\"}}";
    private static final String FAIL_CONTINUE = "{\"child\":{\"type\":\"fail\"}}";
    /** The start guard prices an iterate step at its default 50 items of the child type; {@code fail} costs 20. */
    private static final long FAIL_ITEMS_BALANCE = 2000;

    /** A request carrying this line is answered 500 by the provider stand-in; every other one gets a fixed line. */
    private static final String FAILING_LINE = "FAILING";
    private static final String TRANSLATE_THREE = "{\"child\":{\"type\":\"translate\"},\"maxItems\":3}";
    private static final TranslateProvider PROVIDER = TranslateProvider.start();

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        registry.add("flowcortex.llm.gemini.base-url", PROVIDER::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private StepClosingTestConfig.StepCloser stepCloser;

    @Autowired
    private RunResumption runResumption;

    @Test
    void echoChildrenOverANamedPathComplete() {
        UserView owner = memberWithBalance("named-path", 100);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("plan", "echo", null),
                iterate("each", "{step:plan.scenes}", ECHO), new NewWorkflowStep("after", "echo", null));

        RunView run = start(owner, workflow, "{\"title\":\"T\",\"scenes\":[\"a\",\"b\"]}");

        String gathered = "{\"results\":[\"a\",\"b\"],\"texts\":[\"a\",\"b\"],\"succeeded\":2,\"failed\":0,"
                + "\"total\":2,\"dropped\":0}";
        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps()).extracting(RunStepView::status).containsExactly("completed", "completed", "completed");
        assertThat(run.steps().get(1).output()).isEqualTo(gathered);
        assertThat(run.steps().get(2).output()).isEqualTo(gathered);
        List<Map<String, Object>> items = items(run.steps().get(1).id());
        assertThat(items).extracting(item -> item.get("status")).containsExactly("completed", "completed");
        assertThat(items).extracting(item -> item.get("output")).containsExactly("a", "b");
    }

    @Test
    void continueCompletesWithAllFailedChildren() {
        UserView owner = memberWithBalance("continue", FAIL_ITEMS_BALANCE);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, FAIL_CONTINUE));

        RunView run = start(owner, workflow, "[1,2]");

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps().get(0).output()).isEqualTo("{\"results\":[null,null],\"texts\":[],\"succeeded\":0,"
                + "\"failed\":2,\"total\":2,\"dropped\":0}");
        assertThat(items(run.steps().get(0).id())).extracting(item -> item.get("status"))
                .containsExactly("error", "error");
    }

    @Test
    void failFastErrorsTheRunAndSkipsTheRest() {
        UserView owner = memberWithBalance("fail-fast", FAIL_ITEMS_BALANCE);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null,
                "{\"child\":{\"type\":\"fail\"},\"concurrency\":1,\"onItemError\":\"fail\"}"));

        RunView run = start(owner, workflow, "[1,2,3]");

        assertThat(run.status()).isEqualTo("error");
        RunStepView step = run.steps().get(0);
        assertThat(step.status()).isEqualTo("error");
        assertThat(step.errorCode()).isEqualTo("iterate_item_failed");
        assertThat(step.errorMessage()).startsWith("Item 0 failed");
        assertThat(items(step.id())).extracting(item -> item.get("status"))
                .containsExactly("error", "skipped", "skipped");
    }

    @Test
    void emptyListCompletesAtOnce() {
        UserView owner = memberWithBalance("empty", 100);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, ECHO),
                new NewWorkflowStep("after", "echo", null));

        RunView run = start(owner, workflow, "[]");

        String gathered = "{\"results\":[],\"texts\":[],\"succeeded\":0,\"failed\":0,\"total\":0,\"dropped\":0}";
        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps()).extracting(RunStepView::output).containsExactly(gathered, gathered);
        assertThat(items(run.steps().get(0).id())).isEmpty();
    }

    @Test
    void notAListFailsWithNoChild() {
        UserView owner = memberWithBalance("not-a-list", 100);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, ECHO));

        RunView run = start(owner, workflow, "hello");

        assertThat(run.status()).isEqualTo("error");
        assertThat(run.steps().get(0).errorCode()).isEqualTo("iterate_source_not_list");
        assertThat(items(run.steps().get(0).id())).isEmpty();
    }

    @Test
    void capRunsFirstMaxItemsAndReportsDropped() {
        UserView owner = memberWithBalance("cap", 100);
        WorkflowView workflow = newWorkflow(owner.id(), false,
                iterate("each", null, "{\"child\":{\"type\":\"echo\"},\"maxItems\":3}"));

        RunView run = start(owner, workflow, "[1,2,3,4,5]");

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps().get(0).output()).contains("\"total\":3").contains("\"dropped\":2");
        assertThat(items(run.steps().get(0).id())).hasSize(3);
    }

    @Test
    void failedChildDoesNotKeepTheRunOpen() {
        UserView owner = memberWithBalance("failed-child", FAIL_ITEMS_BALANCE);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, FAIL_CONTINUE),
                new NewWorkflowStep("after", "echo", null));

        RunView run = start(owner, workflow, "[1]");

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps()).hasSize(2).extracting(RunStepView::status).containsExactly("completed", "completed");
        assertThat(items(run.steps().get(0).id())).extracting(item -> item.get("status")).containsExactly("error");
    }

    @Test
    void stopSkipsPendingChildrenAndSettlesCancelled() {
        UserView owner = memberWithBalance("stop-in-flight", 100);
        String token = loginAs(owner);
        Seeded seeded = seedRunning(owner, "running", "pending", "pending", "pending", "pending");

        ResponseEntity<RunResponseView> response = post("/api/v1/runs/" + seeded.runId() + "/cancel", token, null,
                RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("cancelling");
        assertThat(items(seeded.parentId())).extracting(item -> item.get("status"))
                .containsExactly("running", "skipped", "skipped", "skipped", "skipped");

        assertThat(stepCloser.complete(seeded.runId(), seeded.itemIds().get(0), "done")).isTrue();

        RunView stopped = pollUntil(owner.id(), seeded.runId(), run -> "cancelled".equals(run.status()), TIMEOUT);
        assertThat(stopped.steps().get(0).status()).isEqualTo("error");
        assertThat(stopped.steps().get(0).errorCode()).isEqualTo("iterate_cancelled");
        assertThat(items(seeded.parentId()).get(0).get("output")).isEqualTo("done");
    }

    @Test
    void stopWithNoChildInFlightSettlesInTheSameRequest() {
        UserView owner = memberWithBalance("stop-idle", 100);
        String token = loginAs(owner);
        Seeded seeded = seedRunning(owner, "pending", "pending", "pending");

        ResponseEntity<RunResponseView> response = post("/api/v1/runs/" + seeded.runId() + "/cancel", token, null,
                RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("cancelled");
        assertThat(response.getBody().steps()).extracting(RunStepResponseView::status).containsExactly("error",
                "skipped");
        assertThat(items(seeded.parentId())).extracting(item -> item.get("status"))
                .containsExactly("skipped", "skipped", "skipped");
        assertThat(workflowRunService.getRun(owner.id(), seeded.runId()).steps().get(0).errorCode())
                .isEqualTo("iterate_cancelled");
    }

    @Test
    void aStepRetryNamingAChildIsNotFoundAndChangesNothing() {
        UserView owner = memberWithBalance("retry-child", 10);
        String token = loginAs(owner);
        Seeded seeded = seedRunning(owner, "completed");

        ResponseEntity<String> response = post("/api/v1/runs/" + seeded.runId() + "/steps/" + seeded.itemIds().get(0)
                + "/retry", token, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(items(seeded.parentId())).extracting(item -> item.get("status")).containsExactly("completed");
    }

    @Test
    void reaperSweepCompletesAnIterateWhoseAdvanceWasLost() {
        UserView owner = memberWithBalance("lost-advance", 100);
        Seeded seeded = seedRunning(owner, "completed", "completed", "completed");

        stepCloser.reap();

        RunView run = pollUntil(owner.id(), seeded.runId(), view -> "completed".equals(view.status()), TIMEOUT);
        assertThat(run.steps()).extracting(RunStepView::status).containsExactly("completed", "completed");
        assertThat(run.steps().get(0).output()).isEqualTo("{\"results\":[\"item-0\",\"item-1\",\"item-2\"],"
                + "\"texts\":[\"item-0\",\"item-1\",\"item-2\"],\"succeeded\":3,\"failed\":0,\"total\":3,"
                + "\"dropped\":0}");
        assertThat(run.steps().get(1).output()).isEqualTo(run.steps().get(0).output());
    }

    @Test
    void eachChildChargedOnceAndTheParentNever() {
        UserView owner = memberWithBalance("charged", 100);
        WorkflowView workflow = newWorkflow(owner.id(), false,
                iterate("each", null, "{\"child\":{\"type\":\"echo\"},\"maxItems\":3}"));

        RunView run = start(owner, workflow, "[1,2,3]");

        assertThat(run.status()).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ? AND kind = 'debit'",
                Long.class, run.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_step_id = ?", Long.class,
                run.steps().get(0).id())).isZero();
        assertThat(balance(owner)).isEqualTo(97);
    }

    @Test
    void unpaidChildFailsTheIterateWithInsufficientCredits() {
        UserView owner = memberWithBalance("unpaid", 2);
        WorkflowView workflow = newWorkflow(owner.id(), false,
                iterate("each", null, "{\"child\":{\"type\":\"echo\"},\"concurrency\":1,\"maxItems\":4}"));

        RunView started = workflowRunService.startRun(owner.id(), workflow.id(), "[1,2,3,4]", null, List.of(), null,
                null, true);
        RunView run = awaitTerminal(owner.id(), started.id());

        assertThat(run.status()).isEqualTo("error");
        RunStepView step = run.steps().get(0);
        assertThat(step.errorCode()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(step.errorMessage()).isEqualTo("Not enough credits to run this step");
        List<Map<String, Object>> items = items(step.id());
        assertThat(items).extracting(item -> item.get("status"))
                .containsExactly("completed", "completed", "error", "skipped");
        assertThat(items.get(2).get("error_code")).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(balance(owner)).isZero();
    }

    @Test
    void retryReusesCompletedChildrenWithoutCharging() {
        UserView owner = memberWithBalance("retry-reuse", 10);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, ECHO));
        UUID runId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        String config = "{\"child\":{\"type\":\"echo\"},\"concurrency\":1,\"onItemError\":\"fail\"}";
        Timestamp now = Timestamp.from(Instant.now());
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, "
                    + "finished_at, error_message) VALUES (?, ?, ?, 'error', ?, ?, ?, 'Item 2 failed: boom')",
                    runId, workflow.id(), owner.id(), "[\"a\",\"b\",\"c\"]", now, now);
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, config, error_code, "
                    + "error_message, started_at, finished_at) VALUES (?, ?, 1, 'iterate', 'error', ?, "
                    + "CAST(? AS jsonb), 'iterate_item_failed', 'Item 2 failed: boom', ?, ?)", parentId, runId,
                    "[\"a\",\"b\",\"c\"]", config, now, now);
            insertItem(runId, parentId, 0, "completed", "a");
            insertItem(runId, parentId, 1, "completed", "b");
            insertItem(runId, parentId, 2, "error", null);
        });

        runResumption.retryStep(owner.id(), runId, parentId);
        RunView run = pollUntil(owner.id(), runId, view -> "completed".equals(view.status()), TIMEOUT);

        assertThat(run.steps().get(0).output()).contains("\"results\":[\"a\",\"b\",\"c\"]");
        List<Map<String, Object>> second = jdbc.queryForList("SELECT item_index, status, output, attempt FROM run_step "
                + "WHERE parent_step_id = ? AND parent_attempt = 2 ORDER BY item_index", parentId);
        assertThat(second).extracting(item -> item.get("status")).containsExactly("completed", "completed",
                "completed");
        assertThat(second).extracting(item -> item.get("output")).containsExactly("a", "b", "c");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ? AND kind = 'debit'",
                Long.class, runId)).isEqualTo(1);
        assertThat(balance(owner)).isEqualTo(9);
    }

    @Test
    void retryRunsAnItemAgainAndChargesItWhenItsInputChanged() {
        UserView owner = memberWithBalance("retry-changed", 10);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, ECHO));
        UUID runId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        String config = "{\"child\":{\"type\":\"echo\"},\"concurrency\":1,\"onItemError\":\"fail\"}";
        Timestamp now = Timestamp.from(Instant.now());
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, "
                    + "finished_at, error_message) VALUES (?, ?, ?, 'error', ?, ?, ?, 'Item 2 failed: boom')",
                    runId, workflow.id(), owner.id(), "[\"a\",\"b\",\"c\"]", now, now);
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, config, error_code, "
                    + "error_message, started_at, finished_at) VALUES (?, ?, 1, 'iterate', 'error', ?, "
                    + "CAST(? AS jsonb), 'iterate_item_failed', 'Item 2 failed: boom', ?, ?)", parentId, runId,
                    "[\"a\",\"b\",\"c\"]", config, now, now);
            insertItem(runId, parentId, 0, "completed", "x", "x");
            insertItem(runId, parentId, 1, "completed", "b");
            insertItem(runId, parentId, 2, "error", null);
        });

        runResumption.retryStep(owner.id(), runId, parentId);
        pollUntil(owner.id(), runId, view -> "completed".equals(view.status()), TIMEOUT);

        List<Map<String, Object>> second = jdbc.queryForList("SELECT id, item_index, status, output FROM run_step "
                + "WHERE parent_step_id = ? AND parent_attempt = 2 ORDER BY item_index", parentId);
        assertThat(second).extracting(item -> item.get("output")).containsExactly("a", "b", "c");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_step_id = ? AND kind = 'debit'",
                Long.class, second.get(0).get("id"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_step_id = ?", Long.class,
                second.get(1).get("id"))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ? AND kind = 'debit'",
                Long.class, runId)).isEqualTo(2);
        assertThat(balance(owner)).isEqualTo(8);
    }

    @Test
    void translateChildrenAreEachChargedTheirOwnPriceAndTheParentNever() {
        UserView owner = memberWithBalance("translate-charged", 1000);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, TRANSLATE_THREE));

        RunView run = start(owner, workflow, segmentLists("one", "two", "three"));

        assertThat(run.status()).isEqualTo("completed");
        List<Map<String, Object>> items = items(run.steps().get(0).id());
        assertThat(items).extracting(item -> item.get("status")).containsExactly("completed", "completed",
                "completed");
        assertThat(items).extracting(item -> String.valueOf(item.get("output"))).allSatisfy(
                output -> assertThat(output).contains("\"vi\":\"Xin chào\""));
        assertThat(jdbc.queryForList("SELECT amount FROM credit_ledger WHERE run_id = ? AND kind = 'debit'",
                Long.class, run.id())).containsExactly(100L, 100L, 100L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_step_id = ?", Long.class,
                run.steps().get(0).id())).isZero();
        assertThat(charged(run.id())).isEqualTo(300);
        assertThat(balance(owner)).isEqualTo(700);
    }

    @Test
    void aTranslateChildThatFailsOnTheSystemSideIsRefundedAndTheOthersStayCharged() {
        UserView owner = memberWithBalance("translate-refund", 1000);
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, TRANSLATE_THREE));

        RunView run = start(owner, workflow, segmentLists("one", FAILING_LINE, "three"));

        assertThat(run.status()).isEqualTo("completed");
        List<Map<String, Object>> items = items(run.steps().get(0).id());
        assertThat(items).extracting(item -> item.get("status")).containsExactly("completed", "error", "completed");
        assertThat(items.get(1).get("error_code")).isEqualTo("provider_unavailable");
        UUID failedChild = jdbc.queryForObject("SELECT id FROM run_step WHERE parent_step_id = ? AND item_index = 1",
                UUID.class, run.steps().get(0).id());
        assertThat(jdbc.queryForList("SELECT kind FROM credit_ledger WHERE run_step_id = ? ORDER BY kind",
                String.class, failedChild)).containsExactly("debit", "refund");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ? AND kind = 'debit'",
                Long.class, run.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ? AND kind = 'refund'",
                Long.class, run.id())).isEqualTo(1);
        assertThat(charged(run.id())).isEqualTo(200);
        assertThat(balance(owner)).isEqualTo(800);
    }

    /** A list of single-line dub segment lists, one per text: the item shape {@code translate} reads as its input. */
    private static String segmentLists(String... zh) {
        return java.util.Arrays.stream(zh)
                .map(line -> "[{\"index\":1,\"startMs\":500,\"endMs\":3100,\"zh\":\"" + line + "\"}]")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** Debits less refunds of one run, the number the run view reports as its charged credits. */
    private long charged(UUID runId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN kind = 'debit' THEN amount ELSE -amount END), 0) "
                + "FROM credit_ledger WHERE run_id = ? AND kind IN ('debit', 'refund')", Long.class, runId);
    }

    private void insertItem(UUID runId, UUID parentId, int index, String status, String output) {
        insertItem(runId, parentId, index, status, output, "abc".substring(index, index + 1));
    }

    private void insertItem(UUID runId, UUID parentId, int index, String status, String output, String input) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, error_code, "
                + "error_message, started_at, finished_at, parent_step_id, parent_attempt, item_index) VALUES "
                + "(?, ?, 1, 'echo', ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)", UUID.randomUUID(), runId, status, input, output,
                "error".equals(status) ? "boom" : null, "error".equals(status) ? "boom" : null, now, now, parentId,
                index);
    }

    private record Seeded(UUID runId, UUID parentId, List<UUID> itemIds) {
    }

    /**
     * A running run whose iterate step (step 1, picked up, far from its deadline) has one item per entry of
     * {@code itemStatuses}, followed by a pending echo step. Written in one transaction so the run is never
     * seen half built.
     */
    private Seeded seedRunning(UserView owner, String... itemStatuses) {
        WorkflowView workflow = newWorkflow(owner.id(), false, iterate("each", null, ECHO),
                new NewWorkflowStep("after", "echo", null));
        UUID runId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        List<UUID> itemIds = java.util.stream.IntStream.range(0, itemStatuses.length)
                .mapToObj(index -> UUID.randomUUID()).toList();
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp lease = Timestamp.from(Instant.now().plusSeconds(7200));
        Timestamp deadline = Timestamp.from(Instant.now().plusSeconds(3600));
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                    + "VALUES (?, ?, ?, 'running', ?, ?)", runId, workflow.id(), owner.id(), "[0,1,2,3,4]", now);
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, config, started_at, "
                    + "picked_up_at, lease_until, run_deadline) VALUES (?, ?, 1, 'iterate', 'running', ?, "
                    + "CAST(? AS jsonb), ?, ?, ?, ?)", parentId, runId, "[0,1,2,3,4]", ECHO, now, now, lease,
                    deadline);
            for (int index = 0; index < itemStatuses.length; index++) {
                String itemStatus = itemStatuses[index];
                boolean started = !"pending".equals(itemStatus);
                jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, "
                        + "started_at, finished_at, queue_deadline, parent_step_id, parent_attempt, item_index) "
                        + "VALUES (?, ?, 1, 'echo', ?, ?, ?, ?, ?, ?, ?, 1, ?)", itemIds.get(index), runId,
                        itemStatus, "item-" + index, "completed".equals(itemStatus) ? "item-" + index : null,
                        started ? now : null, "completed".equals(itemStatus) ? now : null,
                        "running".equals(itemStatus) ? deadline : null, parentId, index);
            }
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, 2, 'echo', "
                    + "'pending')", UUID.randomUUID(), runId);
        });
        return new Seeded(runId, parentId, itemIds);
    }

    private static NewWorkflowStep iterate(String name, String inputRef, String config) {
        return new NewWorkflowStep(name, "iterate", inputRef, config);
    }

    private RunView start(UserView owner, WorkflowView workflow, String input) {
        return awaitTerminal(owner.id(), workflowRunService.startRun(owner.id(), workflow.id(), input).id());
    }

    @Override
    protected RunView awaitTerminal(UUID ownerId, UUID runId) {
        return pollUntil(ownerId, runId, run -> !"running".equals(run.status()), TIMEOUT);
    }

    /** The items of one iterate step, every parent attempt, by attempt and item index. */
    private List<Map<String, Object>> items(UUID parentId) {
        return jdbc.queryForList("SELECT item_index, status, input, output, error_code, attempt, parent_attempt "
                + "FROM run_step WHERE parent_step_id = ? ORDER BY parent_attempt, item_index", parentId);
    }

    private UserView memberWithBalance(String prefix, long balance) {
        UserView member = newUser(prefix);
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class, member.id()) == 1);
        jdbc.update("UPDATE credit_wallet SET balance = ? WHERE user_id = ?", balance, member.id());
        return member;
    }

    private long balance(UserView member) {
        return jdbc.queryForObject("SELECT balance FROM credit_wallet WHERE user_id = ?", Long.class, member.id());
    }

    /** Answers the translation endpoint the {@code translate} step calls; nothing reaches the real provider. */
    private static final class TranslateProvider {

        private final HttpServer server;

        private TranslateProvider(HttpServer server) {
            this.server = server;
        }

        static TranslateProvider start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                TranslateProvider provider = new TranslateProvider(server);
                server.createContext("/", provider::answer);
                server.start();
                return provider;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void answer(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean failing = request.contains(FAILING_LINE);
            String body = failing ? "{}" : "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                    + "\"[{\\\"index\\\":1,\\\"vi\\\":\\\"Xin chào\\\"}]\"}]}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(failing ? 500 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
        }

        void stop() {
            server.stop(0);
        }
    }
}
