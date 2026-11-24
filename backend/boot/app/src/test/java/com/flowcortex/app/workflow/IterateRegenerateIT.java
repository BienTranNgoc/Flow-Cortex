package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.StepClosingTestConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /api/v1/runs/{id}/steps/{stepId}/children/{itemIndex}/regenerate} over HTTP, with credits on.
 * Runs that end by themselves go through the real engine; the states a refusal needs (a run still running,
 * items mid-regenerate, an item left pending) are seeded with SQL. Same settings as the iterate engine IT, so
 * both share one application context.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.cancel.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
@Import(StepClosingTestConfig.class)
class IterateRegenerateIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String ECHO = "{\"child\":{\"type\":\"echo\"}}";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    record Problem(int status, String detail, String code) {
    }

    record ChildView(UUID id, int itemIndex, String status, int attempt, String output) {
    }

    record StepView(UUID id, int stepOrder, String type, String status, String output, List<ChildView> children) {
    }

    record RegeneratedRun(UUID id, String status, List<StepView> steps) {
    }

    @Test
    void regenerateRunsOnlyThatChildAndRegathers() {
        UserView owner = memberWithBalance("regen-one", 100);
        String token = loginAs(owner);
        RunView run = completedRun(owner, ECHO, "[\"a\",\"b\",\"c\"]");
        UUID parentId = run.steps().get(0).id();
        String laterOutput = run.steps().get(1).output();
        jdbc.update("UPDATE run_step SET input = 'B' WHERE id = ?", itemId(parentId, 1));

        ResponseEntity<RegeneratedRun> response = post(path(run.id(), parentId, 1), token, null,
                RegeneratedRun.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("completed");
        List<ChildView> children = response.getBody().steps().get(0).children();
        assertThat(children).extracting(ChildView::itemIndex).containsExactly(0, 1, 2);
        assertThat(children.get(1).attempt()).isEqualTo(2);
        RunView after = pollUntil(owner.id(), run.id(), view -> view.steps().get(0).output().contains("\"B\""),
                TIMEOUT);
        assertThat(after.status()).isEqualTo("completed");
        assertThat(after.steps().get(0).output()).isEqualTo("{\"results\":[\"a\",\"B\",\"c\"],"
                + "\"texts\":[\"a\",\"B\",\"c\"],\"succeeded\":3,\"failed\":0,\"total\":3,\"dropped\":0}");
        assertThat(after.steps().get(1).output()).isEqualTo(laterOutput);
        assertThat(items(parentId)).extracting(item -> item.get("attempt")).containsExactly(1, 2, 1);
        assertThat(items(parentId)).extracting(item -> item.get("status")).containsOnly("completed");
    }

    @Test
    void regeneratedFailureKeepsThePreviousResult() {
        UserView owner = memberWithBalance("regen-fail", 1000);
        String token = loginAs(owner);
        RunView run = completedRun(owner, "{\"child\":{\"type\":\"fail\"},\"maxItems\":2}", "[1,2]");
        UUID parentId = run.steps().get(0).id();
        String gathered = run.steps().get(0).output();

        ResponseEntity<RegeneratedRun> response = post(path(run.id(), parentId, 0), token, null,
                RegeneratedRun.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).until(() -> {
            Map<String, Object> item = items(parentId).get(0);
            return "error".equals(item.get("status")) && ((Number) item.get("attempt")).intValue() == 2;
        });
        RunView after = workflowRunService.getRun(owner.id(), run.id());
        assertThat(after.status()).isEqualTo("completed");
        assertThat(after.steps().get(0).output()).isEqualTo(gathered);
    }

    @Test
    void anotherMembersRunIs404() {
        UserView owner = memberWithBalance("regen-owner", 100);
        String strangerToken = loginAs(memberWithBalance("regen-stranger", 100));
        RunView run = completedRun(owner, ECHO, "[\"a\"]");
        UUID parentId = run.steps().get(0).id();

        ResponseEntity<Problem> response = post(path(run.id(), parentId, 0), strangerToken, null, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(items(parentId)).extracting(item -> item.get("attempt")).containsExactly(1);
    }

    @Test
    void runNotCompletedIs409() {
        UserView owner = memberWithBalance("regen-running", 100);
        String token = loginAs(owner);
        Seeded seeded = seed(owner, "running", 3, "completed", "completed");

        ResponseEntity<Problem> response = post(path(seeded.runId(), seeded.parentId(), 0), token, null,
                Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().detail()).isEqualTo("An item can be regenerated only once the run has completed");
        assertThat(items(seeded.parentId())).extracting(item -> item.get("attempt")).containsExactly(1, 1);
    }

    @Test
    void aStepThatIsNotAnIterateStepIs409() {
        UserView owner = memberWithBalance("regen-not-iterate", 100);
        String token = loginAs(owner);
        RunView run = completedRun(owner, ECHO, "[\"a\"]");

        ResponseEntity<Problem> response = post(path(run.id(), run.steps().get(1).id(), 0), token, null,
                Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().detail()).isEqualTo(
                "Only a step that repeats a child over a list has items to regenerate");
    }

    @Test
    void itemOutOfRangeIs404() {
        UserView owner = memberWithBalance("regen-range", 100);
        String token = loginAs(owner);
        RunView run = completedRun(owner, ECHO, "[\"a\",\"b\",\"c\"]");
        UUID parentId = run.steps().get(0).id();

        ResponseEntity<Problem> past = post(path(run.id(), parentId, 3), token, null, Problem.class);
        ResponseEntity<Problem> negative = post(path(run.id(), parentId, -1), token, null, Problem.class);

        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(past.getBody().detail()).isEqualTo("This step has no item at that position");
        assertThat(negative.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(items(parentId)).extracting(item -> item.get("attempt")).containsOnly(1);
    }

    @Test
    void regeneratesOverTheCapAre409Busy() {
        UserView owner = memberWithBalance("regen-busy", 100);
        String token = loginAs(owner);
        Seeded seeded = seed(owner, "completed", 2, "running:2", "running:2", "completed", "completed", "completed");

        ResponseEntity<Problem> response = post(path(seeded.runId(), seeded.parentId(), 2), token, null,
                Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().detail())
                .isEqualTo("Too many items of this step are running. Try again when one has finished");
        Map<String, Object> untouched = items(seeded.parentId()).get(2);
        assertThat(untouched.get("status")).isEqualTo("completed");
        assertThat(untouched.get("attempt")).isEqualTo(1);
    }

    @Test
    void balanceBelowPriceIs402AndKeepsTheOutput() {
        UserView owner = memberWithBalance("regen-poor", 100);
        String token = loginAs(owner);
        RunView run = completedRun(owner, ECHO, "[\"a\",\"b\"]");
        UUID parentId = run.steps().get(0).id();
        jdbc.update("UPDATE credit_wallet SET balance = 0 WHERE user_id = ?", owner.id());

        ResponseEntity<Problem> response = post(path(run.id(), parentId, 0), token, null, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(items(parentId)).extracting(item -> item.get("attempt")).containsExactly(1, 1);
        assertThat(items(parentId)).extracting(item -> item.get("output")).containsExactly("a", "b");
        assertThat(workflowRunService.getRun(owner.id(), run.id()).steps().get(0).output())
                .isEqualTo(run.steps().get(0).output());
    }

    @Test
    void twoRacingRegeneratesHaveOneWinner() throws Exception {
        UserView owner = memberWithBalance("regen-race", 100);
        String token = loginAs(owner);
        RunView run = completedRun(owner, ECHO, "[\"a\",\"b\"]");
        UUID parentId = run.steps().get(0).id();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Holding the run's row lock lets both requests read the item as completed before either writes.
            Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM workflow_run WHERE id = ? FOR UPDATE", run.id());
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<Map>> first = pool.submit(
                    () -> post(path(run.id(), parentId, 0), token, null, Map.class));
            Future<ResponseEntity<Map>> second = pool.submit(
                    () -> post(path(run.id(), parentId, 0), token, null, Map.class));
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE "
                            + "'%FROM workflow_run%FOR UPDATE%' AND query NOT LIKE '%pg_stat_activity%'",
                    Long.class) >= 2);
            release.countDown();
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);

            ResponseEntity<Map> one = first.get(30, TimeUnit.SECONDS);
            ResponseEntity<Map> other = second.get(30, TimeUnit.SECONDS);
            assertThat(List.of(one.getStatusCode().value(), other.getStatusCode().value()))
                    .containsExactlyInAnyOrder(200, 409);
            Map<?, ?> loser = one.getStatusCode().value() == 409 ? one.getBody() : other.getBody();
            assertThat(loser.get("detail")).isEqualTo("This item is already being regenerated");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        await().atMost(TIMEOUT).until(() -> "completed".equals(items(parentId).get(0).get("status"))
                && ((Number) items(parentId).get(0).get("attempt")).intValue() == 2);
        assertThat(itemDebits(parentId)).isEqualTo(3);
    }

    @Test
    void pendingChildIsRedispatchedWithoutANewAttempt() {
        UserView owner = memberWithBalance("regen-pending", 100);
        String token = loginAs(owner);
        Seeded seeded = seed(owner, "completed", 3, "completed", "pending:2", "completed");

        ResponseEntity<RegeneratedRun> response = post(path(seeded.runId(), seeded.parentId(), 1), token, null,
                RegeneratedRun.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).until(() -> "completed".equals(items(seeded.parentId()).get(1).get("status")));
        assertThat(items(seeded.parentId()).get(1).get("attempt")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT attempt, kind FROM credit_ledger WHERE run_step_id = ?",
                seeded.itemIds().get(1))).extracting(row -> row.get("attempt")).containsExactly(2);
        assertThat(debits(seeded.runId())).isEqualTo(1);
    }

    @Test
    void regeneratedChildChargedForItsNewAttempt() {
        UserView owner = memberWithBalance("regen-charge", 100);
        String token = loginAs(owner);
        RunView run = completedRun(owner, ECHO, "[\"a\",\"b\"]");
        UUID parentId = run.steps().get(0).id();
        UUID itemId = itemId(parentId, 1);
        assertThat(itemDebits(parentId)).isEqualTo(2);
        long balanceBefore = balance(owner);

        ResponseEntity<RegeneratedRun> response = post(path(run.id(), parentId, 1), token, null,
                RegeneratedRun.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(TIMEOUT).until(() -> "completed".equals(items(parentId).get(1).get("status"))
                && ((Number) items(parentId).get(1).get("attempt")).intValue() == 2);
        assertThat(jdbc.queryForList("SELECT attempt FROM credit_ledger WHERE run_step_id = ? AND kind = 'debit' "
                + "ORDER BY attempt", itemId)).extracting(row -> row.get("attempt")).containsExactly(1, 2);
        assertThat(itemDebits(parentId)).isEqualTo(3);
        assertThat(balance(owner)).isEqualTo(balanceBefore - 1);
    }

    @Test
    void stepRetryOnAChildIs404() {
        UserView owner = memberWithBalance("regen-retry-child", 100);
        String token = loginAs(owner);
        RunView run = completedRun(owner, ECHO, "[\"a\"]");
        UUID parentId = run.steps().get(0).id();

        ResponseEntity<Problem> response = post("/api/v1/runs/" + run.id() + "/steps/" + itemId(parentId, 0)
                + "/retry", token, null, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(items(parentId)).extracting(item -> item.get("attempt")).containsExactly(1);
    }

    @Test
    void aChildIdInPlaceOfTheStepIs404() {
        UserView owner = memberWithBalance("regen-child-id", 100);
        String token = loginAs(owner);
        RunView run = completedRun(owner, ECHO, "[\"a\"]");
        UUID parentId = run.steps().get(0).id();

        ResponseEntity<Problem> response = post(path(run.id(), itemId(parentId, 0), 0), token, null, Problem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(items(parentId)).extracting(item -> item.get("attempt")).containsExactly(1);
    }

    private static String path(UUID runId, UUID stepId, int itemIndex) {
        return "/api/v1/runs/" + runId + "/steps/" + stepId + "/children/" + itemIndex + "/regenerate";
    }

    /** A run of {@code iterate} then an {@code echo} step, run to its end through the engine. */
    private RunView completedRun(UserView owner, String config, String input) {
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("each", "iterate", null, config),
                new NewWorkflowStep("after", "echo", null));
        RunView run = pollUntil(owner.id(), workflowRunService.startRun(owner.id(), workflow.id(), input).id(),
                view -> !"running".equals(view.status()), TIMEOUT);
        assertThat(run.status()).isEqualTo("completed");
        return run;
    }

    private record Seeded(UUID runId, UUID parentId, List<UUID> itemIds) {
    }

    /**
     * A run in {@code runStatus} whose iterate step (step 1) is {@code completed} at attempt 1 with one echo item per
     * entry of {@code items}, then an {@code echo} step. An entry is {@code status} or {@code status:attempt}; the
     * step's output gathers each item's text, so a regathered item has a result to replace.
     */
    private Seeded seed(UserView owner, String runStatus, int concurrency, String... items) {
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("each", "iterate", null, ECHO),
                new NewWorkflowStep("after", "echo", null));
        UUID runId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        List<UUID> itemIds = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int index = 0; index < items.length; index++) {
            itemIds.add(UUID.randomUUID());
            texts.add("\"item-" + index + "\"");
        }
        String gathered = "{\"results\":" + texts + ",\"texts\":" + texts + ",\"succeeded\":" + items.length
                + ",\"failed\":0,\"total\":" + items.length + ",\"dropped\":0}";
        String config = "{\"child\":{\"type\":\"echo\"},\"concurrency\":" + concurrency + "}";
        boolean runDone = "completed".equals(runStatus);
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp deadline = Timestamp.from(Instant.now().plusSeconds(3600));
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, finished_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)", runId, workflow.id(), owner.id(), runStatus, "[]", now,
                    runDone ? now : null);
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, config, "
                    + "started_at, finished_at) VALUES (?, ?, 1, 'iterate', 'completed', '[]', ?, CAST(? AS jsonb), "
                    + "?, ?)", parentId, runId, gathered, config, now, now);
            for (int index = 0; index < items.length; index++) {
                String[] parts = items[index].split(":");
                String itemStatus = parts[0];
                int attempt = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                boolean started = !"pending".equals(itemStatus);
                boolean completed = "completed".equals(itemStatus);
                jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, attempt, "
                        + "started_at, finished_at, queue_deadline, parent_step_id, parent_attempt, item_index) "
                        + "VALUES (?, ?, 1, 'echo', ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)", itemIds.get(index), runId,
                        itemStatus, "item-" + index, completed ? "item-" + index : null, attempt,
                        started ? now : null, completed ? now : null, "running".equals(itemStatus) ? deadline : null,
                        parentId, index);
            }
            jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, output, started_at, "
                    + "finished_at) VALUES (?, ?, 2, 'echo', ?, ?, ?, ?)", UUID.randomUUID(), runId,
                    runDone ? "completed" : "pending", runDone ? gathered : null, runDone ? now : null,
                    runDone ? now : null);
        });
        return new Seeded(runId, parentId, itemIds);
    }

    private UUID itemId(UUID parentId, int index) {
        return jdbc.queryForObject("SELECT id FROM run_step WHERE parent_step_id = ? AND item_index = ?",
                UUID.class, parentId, index);
    }

    /** The items of one iterate step, every parent attempt, by attempt and item index. */
    private List<Map<String, Object>> items(UUID parentId) {
        return jdbc.queryForList("SELECT item_index, status, input, output, attempt FROM run_step "
                + "WHERE parent_step_id = ? ORDER BY parent_attempt, item_index", parentId);
    }

    /** The debits written for the items of one iterate step, whatever the run's other steps were charged. */
    private long itemDebits(UUID parentId) {
        return jdbc.queryForObject("SELECT count(*) FROM credit_ledger l JOIN run_step s ON s.id = l.run_step_id "
                + "WHERE s.parent_step_id = ? AND l.kind = 'debit'", Long.class, parentId);
    }

    private long debits(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_id = ? AND kind = 'debit'",
                Long.class, runId);
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
}
