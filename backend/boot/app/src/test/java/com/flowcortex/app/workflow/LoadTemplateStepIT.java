package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunOptions;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The load_template step through the real engine with credits on: the entry is read as stored when the
 * step runs, only a template-typed entry of the run's own workflow is loadable, and the step is free.
 */
@Testcontainers
@SpringBootTest(properties = {
        "flowcortex.credits.enabled=true",
        "flowcortex.workflow.reaper-interval-ms=3600000"})
class LoadTemplateStepIT extends AbstractWorkflowModuleIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String PROMO = "{\"data\":{\"brief\":\"Spring sale\"}}";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CreditAccounts creditAccounts;

    @Test
    void theFirstStepReadsTheRunInput() {
        UserView owner = memberWithBalance("first-step", 10);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("load", "load_template", null));
        putEntry(workflow.id(), "promo", "template", PROMO);

        RunView run = runToEnd(owner, workflow, "{\"template\":\"promo\"}");

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps().get(0).output()).isEqualTo("{\"brief\":\"Spring sale\"}");
    }

    @Test
    void aLaterStepReadsAFieldOfTheWorkingSet() {
        UserView owner = memberWithBalance("echo-field", 10);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("load", "load_template", null),
                new NewWorkflowStep("show", "echo", "{step:1.brief}"));
        putEntry(workflow.id(), "promo", "template", PROMO);

        RunView run = runToEnd(owner, workflow, "{\"template\":\"promo\"}");

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps().get(1).output()).isEqualTo("Spring sale");
    }

    @Test
    void anEntryChangedBetweenRunsIsReadAsItIsStoredWhenTheStepRuns() {
        UserView owner = memberWithBalance("changed", 10);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("load", "load_template", null));
        putEntry(workflow.id(), "promo", "template", PROMO);
        String input = "{\"template\":\"promo\"}";

        assertThat(runToEnd(owner, workflow, input).steps().get(0).output()).isEqualTo("{\"brief\":\"Spring sale\"}");
        putEntry(workflow.id(), "promo", "template", "{\"data\":{\"brief\":\"Summer sale\"}}");

        assertThat(runToEnd(owner, workflow, input).steps().get(0).output()).isEqualTo("{\"brief\":\"Summer sale\"}");
    }

    @Test
    void decimalsOfTheTemplateReachTheOutputWithTheirDigits() {
        UserView owner = memberWithBalance("decimals", 10);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("load", "load_template", null));
        putEntry(workflow.id(), "promo", "template",
                "{\"data\":{\"pi\":3.141592653589793238462643,\"price\":1.00,\"huge\":1e400}}");

        RunView run = runToEnd(owner, workflow, "{\"template\":\"promo\"}");

        assertThat(run.status()).isEqualTo("completed");
        assertThat(run.steps().get(0).output()).isEqualTo(
                "{\"pi\":3.141592653589793238462643,\"huge\":1" + "0".repeat(400) + ",\"price\":1.00}");
    }

    @Test
    void anUnknownKeyFailsTheRunWithTheNotFoundCode() {
        UserView owner = memberWithBalance("unknown", 10);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("load", "load_template", null));

        RunView run = runToEnd(owner, workflow, "{\"template\":\"missing\"}");

        assertThat(run.status()).isEqualTo("error");
        assertThat(run.steps().get(0).errorCode()).isEqualTo("load_template_not_found");
    }

    @Test
    void aMemberRunningASharedWorkflowCannotLoadAJsonEntryOfIt() {
        UserView ana = memberWithBalance("ana", 10);
        UserView ben = memberWithBalance("ben", 10);
        WorkflowView workflow = newWorkflow(ana.id(), true, new NewWorkflowStep("load", "load_template", null));
        putEntry(workflow.id(), "apiNotes", "json", "{\"secret\":\"token-123\"}");

        RunView run = runToEnd(ben, workflow, "{\"template\":\"apiNotes\"}");

        RunStepView step = run.steps().get(0);
        assertThat(run.status()).isEqualTo("error");
        assertThat(step.errorCode()).isEqualTo("load_template_not_found");
        assertThat(step.output()).isNull();
        assertThat(String.valueOf(step.errorMessage())).doesNotContain("token-123");
        assertThat(String.valueOf(run)).doesNotContain("token-123");
    }

    @Test
    void theStepIsFreeAndOnlyTheEchoIsDebited() {
        UserView owner = memberWithBalance("charge", 10);
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("load", "load_template", null),
                new NewWorkflowStep("show", "echo", "{step:1.brief}"));
        putEntry(workflow.id(), "promo", "template", PROMO);

        assertThat(creditAccounts.estimate(List.of("load_template", "echo"))).isEqualTo(1);
        RunView run = runToEnd(owner, workflow, "{\"template\":\"promo\"}");

        assertThat(run.status()).isEqualTo("completed");
        assertThat(balance(owner)).isEqualTo(9);
        assertThat(jdbc.queryForList("SELECT amount FROM credit_ledger WHERE run_id = ? AND kind = 'debit'",
                Long.class, run.id())).containsExactly(1L);
    }

    private RunView runToEnd(UserView member, WorkflowView workflow, String input) {
        RunView started = workflowRunService.startRun(member.id(), workflow.id(), input, null, null, null,
                RunOptions.DEFAULTS, true);
        return awaitTerminal(member.id(), started.id());
    }

    private void putEntry(UUID workflowId, String key, String dataType, String json) {
        jdbc.update("INSERT INTO workflow_data_store (id, workflow_id, key, data_type, value, size_bytes,"
                + " created_at, updated_at) VALUES (?, ?, ?, ?, ?::jsonb, ?, now(), now())"
                + " ON CONFLICT (workflow_id, key) DO UPDATE SET data_type = excluded.data_type,"
                + " value = excluded.value, size_bytes = excluded.size_bytes, updated_at = now()",
                UUID.randomUUID(), workflowId, key, dataType, json, json.length());
    }

    private UserView memberWithBalance(String prefix, long balance) {
        UserView member = newUser(prefix);
        await(() -> jdbc.queryForObject("SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class,
                member.id()) == 1);
        jdbc.update("UPDATE credit_wallet SET balance = ? WHERE user_id = ?", balance, member.id());
        return member;
    }

    private long balance(UserView member) {
        return jdbc.queryForObject("SELECT balance FROM credit_wallet WHERE user_id = ?", Long.class, member.id());
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
