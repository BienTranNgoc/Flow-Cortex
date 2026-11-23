package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flowcortex.app.chat.ChatIt;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What a review step reads and what it costs, over HTTP. Shares the chat ITs' context (credits on),
 * so every member below is funded first.
 */
@ChatIt
class ReviewContentIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    protected UserView newUser(String prefix) {
        UserView user = super.newUser(prefix);
        await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM credit_wallet WHERE user_id = ?", Long.class, user.id()) == 1);
        jdbc.update("UPDATE credit_wallet SET balance = 1000000 WHERE user_id = ?", user.id());
        return user;
    }

    @Test
    void namedReferenceCheckpointShowsTheNamedField() {
        UserView owner = newUser("review-named");
        String token = loginAs(owner);
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("other", "echo", null),
                new NewWorkflowStep("review", "APPROVAL_CHECKPOINT", "{step:1.body}"));

        UUID runId = startRun(token, workflow, "{\"title\":\"A\",\"body\":\"B\"}");
        pollUntil(owner.id(), runId, run -> "waiting_approval".equals(run.status()), TIMEOUT);

        ReviewTaskView[] tasks = get("/api/v1/runs/" + runId + "/review-tasks", token, ReviewTaskView[].class)
                .getBody();
        assertThat(tasks).hasSize(1);
        assertThat(tasks[0].originalPayload()).isEqualTo("B");
    }

    @Test
    void missingFieldFailsTheRunWithoutAReviewTask() {
        UserView owner = newUser("review-missing");
        String token = loginAs(owner);
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("review", "qa.review", "{step:1.body}"));

        UUID runId = startRun(token, workflow, "{\"title\":\"A\"}");
        RunView failed = pollUntil(owner.id(), runId, run -> "error".equals(run.status()), TIMEOUT);

        assertThat(failed.steps().get(1).status()).isEqualTo("error");
        assertThat(failed.steps().get(1).errorMessage()).contains("{step:1.body}");
        assertThat(get("/api/v1/runs/" + runId + "/review-tasks", token, ReviewTaskView[].class).getBody())
                .isEmpty();
    }

    @Test
    void workflowWithQaReviewStartsWithCreditsOnAndEstimatesAsWithout() {
        UserView owner = newUser("review-free");
        String token = loginAs(owner);
        WorkflowView plain = newWorkflow(owner.id(), false, new NewWorkflowStep("draft", "echo", null));
        WorkflowView withReview = newWorkflow(owner.id(), false,
                new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("ask", "qa.review", null));

        assertThat(estimate(token, withReview)).isNotNull().isEqualTo(estimate(token, plain));

        UUID runId = startRun(token, withReview, "Draft post");
        pollUntil(owner.id(), runId, run -> "waiting_approval".equals(run.status()), TIMEOUT);
        RunResponseView run = get("/api/v1/runs/" + runId, token, RunResponseView.class).getBody();
        UUID reviewStepId = run.steps().get(1).id();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE run_step_id = ?", Long.class,
                reviewStepId)).isZero();
    }

    private UUID startRun(String token, WorkflowView workflow, String input) {
        ResponseEntity<RunResponseView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRunRequestView(input), RunResponseView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        return started.getBody().id();
    }

    private Long estimate(String token, WorkflowView workflow) {
        return get("/api/v1/workflows/" + workflow.id(), token, PricedWorkflowView.class).getBody()
                .estimatedCredits();
    }

    record ReviewTaskView(UUID id, String reviewType, String status, String originalPayload) {
    }

    record PricedWorkflowView(UUID id, Long estimatedCredits) {
    }
}
