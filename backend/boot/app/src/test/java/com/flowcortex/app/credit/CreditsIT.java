package com.flowcortex.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.app.workflow.AbstractWorkflowHttpIT;
import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.user.api.UserActivatedEvent;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Run credits with the switch on. {@code echo} costs 10 and {@code fail} 50 here, so the scenarios
 * read as the spec's: a workflow of echo then fail is estimated at 60. Balances are set with SQL
 * once the signup grant has created the wallet; the sweep is pushed out to an hour so it never
 * advances a seeded run.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.credits.step-costs.echo=10",
        "flowcortex.credits.step-costs.fail=50",
        "flowcortex.workflow.cancel.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class CreditsIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CreditAccounts creditAccounts;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactionTemplate;

    record StartRequest(String input, Boolean acknowledgeCost) {
    }

    record CreditsProblem(int status, String detail, String code, Long balance, Long required, Long shortfall) {
    }

    record BalanceView(long balance) {
    }

    record PricedWorkflowView(UUID id, String name, Long estimatedCredits) {
    }

    record ChargedRunView(UUID id, String status, Long chargedCredits) {
    }

    @Test
    void aBalanceBelowTheEstimateIsRefusedWith402AndNoRunIsCreated() {
        UserView member = memberWithBalance("short", 30);
        WorkflowView workflow = echoThenFail(member);

        ResponseEntity<CreditsProblem> response = start(workflow, loginAs(member), null, CreditsProblem.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        CreditsProblem problem = response.getBody();
        assertThat(problem.code()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(problem.balance()).isEqualTo(30);
        assertThat(problem.required()).isEqualTo(60);
        assertThat(problem.shortfall()).isEqualTo(30);
        assertThat(problem.detail()).isEqualTo("Not enough credits: you have 30, this run needs 60 (short 30).");
        assertThat(runCount(workflow)).isZero();
        assertThat(balance(member)).isEqualTo(30);
    }

    @Test
    void aZeroBalanceIsRefusedWhetherOrNotTheCostWasAcknowledged() {
        UserView member = memberWithBalance("empty", 0);
        WorkflowView workflow = echoThenFail(member);
        String token = loginAs(member);

        ResponseEntity<CreditsProblem> plain = start(workflow, token, null, CreditsProblem.class);
        ResponseEntity<CreditsProblem> acknowledged = start(workflow, token, true, CreditsProblem.class);

        assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(acknowledged.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(acknowledged.getBody().code()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(acknowledged.getBody().balance()).isZero();
        assertThat(acknowledged.getBody().shortfall()).isEqualTo(60);
        assertThat(runCount(workflow)).isZero();
    }

    @Test
    void anAcknowledgedRunStartsAndStopsWithNotEnoughCreditsAtTheStepItCannotAfford() {
        UserView member = memberWithBalance("run-anyway", 30);
        WorkflowView workflow = echoThenFail(member);

        ResponseEntity<ChargedRunView> started = start(workflow, loginAs(member), true, ChargedRunView.class);

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = started.getBody().id();
        RunView finished = pollUntil(member.id(), runId, run -> "error".equals(run.status()), TIMEOUT);
        RunStepView first = finished.steps().get(0);
        RunStepView second = finished.steps().get(1);
        assertThat(first.status()).isEqualTo("completed");
        assertThat(first.output()).isNotNull();
        assertThat(second.status()).isEqualTo("error");
        assertThat(second.errorCode()).isEqualTo("INSUFFICIENT_CREDITS");
        assertThat(second.errorMessage()).isEqualTo("Not enough credits to run this step");
        assertThat(balance(member)).isEqualTo(20);
        assertThat(ledger(runId, "debit")).containsExactly(10L);
        assertThat(ledger(runId, "refund")).isEmpty();
    }

    @Test
    void aStepThatFailsForASystemReasonIsRefundedOnceAndCompletedStepsStayChargedWithTheirOutput() {
        UserView member = memberWithBalance("refund", 100);
        WorkflowView workflow = echoThenFail(member);
        String token = loginAs(member);

        UUID runId = start(workflow, token, null, ChargedRunView.class).getBody().id();
        RunView finished = pollUntil(member.id(), runId, run -> "error".equals(run.status()), TIMEOUT);

        RunStepView failed = finished.steps().get(1);
        assertThat(failed.errorCode()).isNull();
        assertThat(finished.steps().get(0).status()).isEqualTo("completed");
        assertThat(finished.steps().get(0).output()).isNotNull();
        assertThat(ledger(runId, "debit")).containsExactlyInAnyOrder(10L, 50L);
        assertThat(ledger(runId, "refund")).containsExactly(50L);
        assertThat(balance(member)).isEqualTo(90);
        assertThat(get("/api/v1/runs/" + runId, token, ChargedRunView.class).getBody().chargedCredits())
                .isEqualTo(10);

        creditAccounts.refundStep(runId, failed.id(), failed.errorCode());

        assertThat(ledger(runId, "refund")).containsExactly(50L);
        assertThat(balance(member)).isEqualTo(90);
    }

    @Test
    void aStepRejectedAtReviewIsNotRefunded() {
        UserView member = memberWithBalance("rejected", 100);
        UUID runId = seedRun(member, "error", "completed", "error");
        UUID stepId = stepIds(runId).get(1);
        chargeBySql(member, runId, stepId, 50);

        creditAccounts.refundStep(runId, stepId, "APPROVAL_REJECTED");

        assertThat(ledger(runId, "refund")).isEmpty();
        assertThat(balance(member)).isEqualTo(50);
    }

    @Test
    void stoppingARunRefundsNothing() {
        UserView member = memberWithBalance("stopped", 100);
        String token = loginAs(member);
        UUID runId = seedRun(member, "running", "completed", "pending");
        chargeBySql(member, runId, stepIds(runId).get(0), 10);

        ResponseEntity<ChargedRunView> stopped = post("/api/v1/runs/" + runId + "/cancel", token, null,
                ChargedRunView.class);

        assertThat(stopped.getBody().status()).isEqualTo("cancelled");
        assertThat(stopped.getBody().chargedCredits()).isEqualTo(10);
        assertThat(ledger(runId, "refund")).isEmpty();
        assertThat(balance(member)).isEqualTo(90);
    }

    @Test
    void theRunShowsWhatItCostAndTheWorkflowWhatItShouldCost() {
        UserView member = memberWithBalance("priced", 1000);
        String token = loginAs(member);
        WorkflowView workflow = echoThenFail(member);
        UUID runId = seedRun(member, "running", "completed", "running");
        List<UUID> steps = stepIds(runId);
        chargeBySql(member, runId, steps.get(0), 10);
        chargeBySql(member, runId, steps.get(1), 50);

        assertThat(get("/api/v1/runs/" + runId, token, ChargedRunView.class).getBody().chargedCredits())
                .isEqualTo(60);
        assertThat(get("/api/v1/workflows/" + workflow.id(), token, PricedWorkflowView.class).getBody()
                .estimatedCredits()).isEqualTo(60);
        assertThat(get("/api/v1/credits/balance", token, BalanceView.class).getBody().balance()).isEqualTo(940);
    }

    @Test
    void aNewAccountIsGrantedOneThousandCreditsOnceEvenWhenTheEventIsHandledAgain() {
        UserView member = newUser("signup");
        awaitWallet(member.id());

        transactionTemplate.executeWithoutResult(status -> events.publishEvent(new UserActivatedEvent(member.id())));
        await(() -> outstandingGrantPublications(member.id()) == 0);

        assertThat(balance(member)).isEqualTo(1000);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_ledger WHERE user_id = ? AND kind = 'grant'",
                Long.class, member.id())).isEqualTo(1);
        assertThat(get("/api/v1/credits/balance", loginAs(member), BalanceView.class).getBody().balance())
                .isEqualTo(1000);
    }

    @Test
    void twoStepsStartingAtOnceOnAWalletThatAffordsOneChargeExactlyOne() throws Exception {
        UserView member = memberWithBalance("race", 60);
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Boolean>> debits = List.of(
                () -> {
                    go.await();
                    return creditAccounts.debitForStep(member.id(), runA, UUID.randomUUID(), "fail");
                },
                () -> {
                    go.await();
                    return creditAccounts.debitForStep(member.id(), runB, UUID.randomUUID(), "fail");
                });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> debit : debits) {
                results.add(pool.submit(debit));
            }
            go.countDown();
            long succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(member)).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM credit_ledger WHERE user_id = ? AND kind = 'debit'",
                Long.class, member.id())).isEqualTo(1);
    }

    private WorkflowView echoThenFail(UserView owner) {
        return newWorkflow(owner.id(), false, new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("break", "fail", null));
    }

    private <T> ResponseEntity<T> start(WorkflowView workflow, String token, Boolean acknowledgeCost,
            Class<T> responseType) {
        return post("/api/v1/workflows/" + workflow.id() + "/runs", token, new StartRequest("hello", acknowledgeCost),
                responseType);
    }

    private UserView memberWithBalance(String prefix, long balance) {
        UserView member = newUser(prefix);
        awaitWallet(member.id());
        jdbcTemplate.update("UPDATE credit_wallet SET balance = ? WHERE user_id = ?", balance, member.id());
        return member;
    }

    /** The signup grant runs after the account commits, on another thread; it creates the wallet. */
    private void awaitWallet(UUID userId) {
        await(() -> jdbcTemplate.queryForObject("SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class,
                userId) == 1);
    }

    private long balance(UserView member) {
        return jdbcTemplate.queryForObject("SELECT balance FROM credit_wallet WHERE user_id = ?", Long.class,
                member.id());
    }

    private List<Long> ledger(UUID runId, String kind) {
        return jdbcTemplate.queryForList("SELECT amount FROM credit_ledger WHERE run_id = ? AND kind = ?", Long.class,
                runId, kind);
    }

    private long runCount(WorkflowView workflow) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                workflow.id());
    }

    private List<UUID> stepIds(UUID runId) {
        return jdbcTemplate.queryForList("SELECT id FROM run_step WHERE run_id = ? ORDER BY step_order", UUID.class,
                runId);
    }

    /** A charge as the claim makes it: the wallet down by the cost and one debit row for the step. */
    private void chargeBySql(UserView member, UUID runId, UUID stepId, long amount) {
        jdbcTemplate.update("UPDATE credit_wallet SET balance = balance - ? WHERE user_id = ?", amount, member.id());
        jdbcTemplate.update("INSERT INTO credit_ledger (id, user_id, run_id, run_step_id, kind, amount, created_at) "
                + "VALUES (?, ?, ?, ?, 'debit', ?, ?)", UUID.randomUUID(), member.id(), runId, stepId, amount,
                Timestamp.from(Instant.now()));
    }

    /** A run of a fresh workflow in {@code runStatus}, one {@code echo} step per entry of {@code stepStatuses}. */
    private UUID seedRun(UserView owner, String runStatus, String... stepStatuses) {
        NewWorkflowStep[] definition = new NewWorkflowStep[stepStatuses.length];
        for (int i = 0; i < stepStatuses.length; i++) {
            definition[i] = new NewWorkflowStep("step-" + (i + 1), "echo", null);
        }
        WorkflowView workflow = newWorkflow(owner.id(), false, definition);
        UUID runId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        boolean finished = "completed".equals(runStatus) || "error".equals(runStatus);
        jdbcTemplate.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, "
                + "finished_at) VALUES (?, ?, ?, ?, 'in', ?, ?)", runId, workflow.id(), owner.id(), runStatus, now,
                finished ? now : null);
        for (int i = 0; i < stepStatuses.length; i++) {
            String status = stepStatuses[i];
            boolean started = !"pending".equals(status);
            boolean closed = "completed".equals(status) || "error".equals(status);
            jdbcTemplate.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, "
                    + "started_at, finished_at, queue_deadline) VALUES (?, ?, ?, 'echo', ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), runId, i + 1, status, started ? "in" : null,
                    "completed".equals(status) ? "in" : null, started ? now : null, closed ? now : null,
                    "running".equals(status) ? Timestamp.from(Instant.now().plusSeconds(1800)) : null);
        }
        return runId;
    }

    private long outstandingGrantPublications(UUID userId) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication "
                + "WHERE event_type LIKE '%UserActivatedEvent%' AND serialized_event LIKE ? AND completion_date IS NULL",
                Long.class, "%" + userId + "%");
        return count == null ? 0 : count;
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + TIMEOUT);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
    }
}
