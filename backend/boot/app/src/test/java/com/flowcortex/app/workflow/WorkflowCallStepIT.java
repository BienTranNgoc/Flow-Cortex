package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.StepClosingTestConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code workflow.call} through the real engine, with credits and the cancel switch on. The first
 * test that loads the context with the step registered, so start-up also proves the bean wiring and
 * the price coverage. Rows are found by run and workflow id, never by table count, because the
 * database is shared with other ITs. The sweep runs every half second so the reaper case needs no
 * hook into it.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.cancel.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=500"})
@AutoConfigureTestRestTemplate
@Import(StepClosingTestConfig.class)
class WorkflowCallStepIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CreditAccounts creditAccounts;

    @Autowired
    private StepClosingTestConfig.StepCloser stepCloser;

    @Test
    void theStepTypeIsPricedFreeWithNoQuantityParameters() {
        assertThat(jdbc.queryForObject("SELECT estimator_kind FROM capability_pricing WHERE capability = "
                + "'workflow.call'", String.class)).isEqualTo("free");
        assertThat(jdbc.queryForObject("SELECT cardinality(quantity_params) FROM capability_pricing WHERE "
                + "capability = 'workflow.call'", Integer.class)).isZero();
    }

    @Test
    void theCalledWorkflowsStepsAreChargedOnceAndTheCallStepNothing() {
        UserView owner = memberWithBalance("charged", 30);
        WorkflowView child = echoes(owner, 2);
        WorkflowView parent = newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null),
                call("call", child.id()));
        assertThat(creditAccounts.estimate(List.of("echo", "workflow.call"))).isEqualTo(1);

        RunView run = awaitTerminal(owner.id(), workflowRunService.startRun(owner.id(), parent.id(), "hello").id());

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps()).hasSize(2);
        RunView childRun = childOf(owner, run.id());
        assertThat(childRun.status()).isEqualTo("completed");
        assertThat(childRun.steps()).hasSize(2);
        assertThat(run.steps().get(1).output()).isEqualTo(childRun.steps().get(1).output()).isEqualTo("hello");
        assertThat(balance(owner)).isEqualTo(27);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE user_id = ? AND kind = 'debit'",
                Long.class, owner.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_step_id = ?", Long.class,
                run.steps().get(1).id())).isZero();
    }

    @Test
    void theCalledWorkflowsLastOutputFlowsOnToTheNextStep() {
        UserView owner = memberWithBalance("flows-on", 30);
        WorkflowView child = echoes(owner, 2);
        WorkflowView parent = newWorkflow(owner.id(), false, call("call", child.id()),
                new NewWorkflowStep("after", "echo", null));

        RunView run = awaitTerminal(owner.id(), workflowRunService.startRun(owner.id(), parent.id(), "payload").id());

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps()).extracting(RunStepView::output).containsExactly("payload", "payload");
    }

    @Test
    void aWorkflowCallingItselfEndsWithoutAChildRun() {
        UserView owner = memberWithBalance("self", 30);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null));
        workflowService.addStep(owner.id(), workflow.id(), callStep("call", workflow.id()));

        RunView run = awaitTerminal(owner.id(), workflowRunService.startRun(owner.id(), workflow.id(), "x").id());

        assertThat(run.status()).isEqualTo("error");
        assertThat(run.steps().get(1).errorCode()).isEqualTo("workflow_call_cycle");
        assertThat(childRunIds(run.id())).isEmpty();
    }

    @Test
    void aTwoLevelCycleIsRefusedWhereItCloses() {
        UserView owner = memberWithBalance("cycle", 60);
        WorkflowView b = newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null));
        WorkflowView a = newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null),
                call("call-b", b.id()));
        workflowService.addStep(owner.id(), b.id(), callStep("call-a", a.id()));

        RunView run = awaitTerminal(owner.id(), workflowRunService.startRun(owner.id(), a.id(), "x").id());

        assertThat(run.status()).isEqualTo("error");
        assertThat(run.steps().get(1).errorCode()).isEqualTo("workflow_call_child_failed");
        RunView middle = childOf(owner, run.id());
        assertThat(middle.workflowId()).isEqualTo(b.id());
        assertThat(middle.status()).isEqualTo("error");
        assertThat(middle.steps().get(1).errorCode()).isEqualTo("workflow_call_cycle");
        assertThat(childRunIds(middle.id())).isEmpty();
    }

    @Test
    void aGreatGrandchildIsRefusedAndNoRunIsCreatedForIt() {
        UserView owner = memberWithBalance("deep", 60);
        WorkflowView d = echoes(owner, 1);
        WorkflowView c = newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null),
                call("call-d", d.id()));
        WorkflowView b = newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null),
                call("call-c", c.id()));
        WorkflowView a = newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null),
                call("call-b", b.id()));

        RunView run = awaitTerminal(owner.id(), workflowRunService.startRun(owner.id(), a.id(), "x").id());

        assertThat(run.status()).isEqualTo("error");
        RunView second = childOf(owner, childOf(owner, run.id()).id());
        assertThat(second.workflowId()).isEqualTo(c.id());
        assertThat(second.steps().get(1).errorCode()).isEqualTo("workflow_call_too_deep");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_run WHERE workflow_id = ?", Long.class,
                d.id())).isZero();
    }

    @Test
    void aFailingChildFailsTheCallingStepAndRun() {
        UserView owner = memberWithBalance("child-fails", 100);
        WorkflowView child = newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("boom", "fail", null));
        WorkflowView parent = newWorkflow(owner.id(), false, call("call", child.id()));

        RunView run = awaitTerminal(owner.id(), workflowRunService.startRun(owner.id(), parent.id(), "x").id());

        assertThat(run.status()).isEqualTo("error");
        assertThat(run.steps().get(0).errorCode()).isEqualTo("workflow_call_child_failed");
        assertThat(childOf(owner, run.id()).status()).isEqualTo("error");
    }

    @Test
    void stoppingTheParentStopsItsChildUnderTheChildsOwner() {
        UserView owner = memberWithBalance("stop-tree", 30);
        String token = loginAs(owner);
        Tree tree = seedTree(owner, Instant.now().plusSeconds(3600));

        ResponseEntity<String> response = post("/api/v1/runs/" + tree.parentRunId + "/cancel", token, null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(workflowRunService.getRun(owner.id(), tree.parentRunId).status()).isEqualTo("cancelling");
        RunView child = workflowRunService.getRun(owner.id(), tree.childRunId);
        assertThat(child.status()).isEqualTo("cancelling");
        assertThat(child.steps()).extracting(RunStepView::status).containsExactly("running", "skipped");

        assertThat(stepCloser.fail(child.id(), child.steps().get(0).id(), "stopped", "stopped")).isTrue();

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(workflowRunService.getRun(owner.id(), tree.childRunId).status()).isEqualTo("cancelled");
            RunView parent = workflowRunService.getRun(owner.id(), tree.parentRunId);
            assertThat(parent.steps().get(0).errorCode()).isEqualTo("workflow_call_child_cancelled");
            assertThat(parent.status()).isEqualTo("cancelled");
        });
    }

    @Test
    void aReapedCallStepStopsItsChildAndFailsTheRun() {
        UserView owner = memberWithBalance("reaped", 30);
        Tree tree = seedTree(owner, Instant.now().minusSeconds(5));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            RunView parent = workflowRunService.getRun(owner.id(), tree.parentRunId);
            assertThat(parent.status()).isEqualTo("error");
            assertThat(parent.steps().get(0).errorCode()).isEqualTo("run_timeout");
        });
        assertThat(workflowRunService.getRun(owner.id(), tree.childRunId).status()).isEqualTo("cancelling");
    }

    private record Tree(UUID parentRunId, UUID childRunId) {
    }

    /** A parent run whose call step is picked up and waiting, with a child run in flight (running, pending). */
    private Tree seedTree(UserView owner, Instant runDeadline) {
        WorkflowView child = newWorkflow(owner.id(), false, new NewWorkflowStep("a", "probe", null),
                new NewWorkflowStep("b", "probe", null));
        WorkflowView parent = newWorkflow(owner.id(), false, call("call", child.id()));
        UUID parentRunId = UUID.randomUUID();
        UUID parentStepId = UUID.randomUUID();
        UUID childRunId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                + "VALUES (?, ?, ?, 'running', 'in', ?)", parentRunId, parent.id(), owner.id(), now);
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, started_at, picked_up_at, "
                + "lease_until, run_deadline) VALUES (?, ?, 1, 'workflow.call', 'running', 'in', ?, ?, ?, ?)",
                parentStepId, parentRunId, now, now, Timestamp.from(Instant.now().plusSeconds(7200)),
                Timestamp.from(runDeadline));
        jdbc.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at, parent_run_id, "
                + "parent_step_id, parent_step_attempt, call_depth) VALUES (?, ?, ?, 'running', 'in', ?, ?, ?, 1, 1)",
                childRunId, child.id(), owner.id(), now, parentRunId, parentStepId);
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, started_at, queue_deadline) "
                + "VALUES (?, ?, 1, 'probe', 'running', 'in', ?, ?)", UUID.randomUUID(), childRunId, now,
                Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("INSERT INTO run_step (id, run_id, step_order, type, status) VALUES (?, ?, 2, 'probe', "
                + "'pending')", UUID.randomUUID(), childRunId);
        return new Tree(parentRunId, childRunId);
    }

    private WorkflowView echoes(UserView owner, int count) {
        NewWorkflowStep[] steps = new NewWorkflowStep[count];
        for (int i = 0; i < count; i++) {
            steps[i] = new NewWorkflowStep("echo-" + (i + 1), "echo", null);
        }
        return newWorkflow(owner.id(), false, steps);
    }

    private static NewWorkflowStep call(String name, UUID workflowId) {
        return new NewWorkflowStep(name, "workflow.call", null, "{\"workflowId\":\"" + workflowId + "\"}");
    }

    private static NewStep callStep(String name, UUID workflowId) {
        return new NewStep(name, "workflow.call", null, "{\"workflowId\":\"" + workflowId + "\"}");
    }

    @Override
    protected RunView awaitTerminal(UUID ownerId, UUID runId) {
        return pollUntil(ownerId, runId, run -> !"running".equals(run.status()), TIMEOUT);
    }

    private List<UUID> childRunIds(UUID parentRunId) {
        return jdbc.queryForList("SELECT id FROM workflow_run WHERE parent_run_id = ?", UUID.class, parentRunId);
    }

    /** The one child of a run, waited for until it ends. */
    private RunView childOf(UserView owner, UUID parentRunId) {
        List<UUID> children = childRunIds(parentRunId);
        assertThat(children).hasSize(1);
        return awaitTerminal(owner.id(), children.get(0));
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
