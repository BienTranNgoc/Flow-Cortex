package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code POST /api/v1/runs/{id}/steps/{stepId}/retry|reset|execute} and {@code POST
 * /api/v1/runs/{id}/retry} over HTTP. Failed runs are seeded with SQL (the failure itself is not
 * under test) and the steps are inline {@code echo} steps, so a resumed run reaches its end in-process.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "flowcortex.workflow.reaper-interval-ms=3600000")
@AutoConfigureTestRestTemplate
class RunResumptionEndpointsIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    record ProblemView(int status, String detail, String code, java.util.Map<String, Object> errors) {
    }

    @Test
    void retryingTheFailedStepReopensTheRunAndItCompletes() {
        UserView owner = newUser("resume-retry");
        String token = loginAs(owner);
        UUID runId = seedFailedRun(owner, "lease_expired");
        UUID stepId = stepId(runId, 1);

        ResponseEntity<RunResponseView> response = post(stepPath(runId, stepId, "retry"), token, null,
                RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("running");
        assertThat(pollUntil(owner.id(), runId, run -> "completed".equals(run.status()), TIMEOUT).steps())
                .extracting(step -> step.status()).containsExactly("completed");
    }

    @Test
    void resetLeavesTheRunFailedAndExecuteThenFinishesIt() {
        UserView owner = newUser("resume-reset-execute");
        String token = loginAs(owner);
        UUID runId = seedFailedRun(owner, "INSUFFICIENT_CREDITS");
        UUID stepId = stepId(runId, 1);

        ResponseEntity<RunResponseView> reset = post(stepPath(runId, stepId, "reset"), token, null,
                RunResponseView.class);

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody().status()).isEqualTo("error");
        assertThat(reset.getBody().steps()).extracting(RunStepResponseView::status).containsExactly("pending");

        ResponseEntity<RunResponseView> executed = post(stepPath(runId, stepId, "execute"), token, null,
                RunResponseView.class);

        assertThat(executed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pollUntil(owner.id(), runId, run -> "completed".equals(run.status()), TIMEOUT).status())
                .isEqualTo("completed");
    }

    @Test
    void retryingARunStartsANewRunThatReusesTheCompletedSteps() {
        UserView owner = newUser("resume-run-retry");
        String token = loginAs(owner);
        UUID runId = seedFailedRun(owner, "lease_expired", true);

        ResponseEntity<RunResponseView> response = post("/api/v1/runs/" + runId + "/retry", token, null,
                RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID newRunId = response.getBody().id();
        assertThat(newRunId).isNotEqualTo(runId);
        assertThat(response.getBody().steps().get(0).status()).isEqualTo("completed");
        RunView finished = pollUntil(owner.id(), newRunId, run -> "completed".equals(run.status()), TIMEOUT);
        assertThat(finished.steps()).extracting(step -> step.status()).containsExactly("completed", "completed");
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("error");
    }

    @Test
    void anOutOfRangeStartStepIsRejectedAsUnprocessable() {
        UserView owner = newUser("resume-bad-from");
        String token = loginAs(owner);
        UUID runId = seedFailedRun(owner, "lease_expired");

        ResponseEntity<ProblemView> response = post("/api/v1/runs/" + runId + "/retry", token,
                new RetryRunRequestView(0, null), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(422));
        assertThat(response.getBody().code()).isEqualTo("WORKFLOW_RETRY_FROM_INVALID");
        assertThat(response.getBody().errors()).containsKey("fromStepOrder");
    }

    @Test
    void aVietnameseCallerRetryingAStepOfACompletedRunGetsTheVietnameseConflict() {
        UserView owner = newUser("resume-vi");
        String token = loginAs(owner);
        UUID runId = seedCompletedRun(owner);
        UUID stepId = stepId(runId, 1);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "vi");

        ResponseEntity<ProblemView> response = restTemplate.exchange(stepPath(runId, stepId, "retry"),
                HttpMethod.POST, new HttpEntity<>(null, headers), ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().detail()).startsWith("Chỉ có thể chạy lại hoặc đặt lại bước");
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("completed");
    }

    @Test
    void anotherMembersRunIsNotFoundForEveryRoute() {
        UserView owner = newUser("resume-owner");
        String strangerToken = loginAs(newUser("resume-stranger"));
        UUID runId = seedFailedRun(owner, "lease_expired");
        UUID stepId = stepId(runId, 1);

        for (String path : new String[] {stepPath(runId, stepId, "retry"), stepPath(runId, stepId, "reset"),
                stepPath(runId, stepId, "execute"), "/api/v1/runs/" + runId + "/retry"}) {
            ResponseEntity<ProblemView> response = post(path, strangerToken, null, ProblemView.class);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("error");
    }

    private static String stepPath(UUID runId, UUID stepId, String operation) {
        return "/api/v1/runs/" + runId + "/steps/" + stepId + "/" + operation;
    }

    private UUID stepId(UUID runId, int order) {
        return jdbcTemplate.queryForObject("SELECT id FROM run_step WHERE run_id = ? AND step_order = ?", UUID.class,
                runId, order);
    }

    private UUID seedFailedRun(UserView owner, String errorCode) {
        return seedFailedRun(owner, errorCode, false);
    }

    /** An {@code error} run whose last step failed with {@code errorCode}; a completed first step precedes it when asked. */
    private UUID seedFailedRun(UserView owner, String errorCode, boolean withCompletedFirstStep) {
        WorkflowView workflow = withCompletedFirstStep
                ? newWorkflow(owner.id(), false, new NewWorkflowStep("first", "echo", null),
                        new NewWorkflowStep("second", "echo", null))
                : newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        UUID runId = insertRun(owner, workflow, "error");
        int order = 1;
        if (withCompletedFirstStep) {
            insertStep(runId, order++, "completed", null);
        }
        insertStep(runId, order, "error", errorCode);
        return runId;
    }

    private UUID seedCompletedRun(UserView owner) {
        WorkflowView workflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));
        UUID runId = insertRun(owner, workflow, "completed");
        insertStep(runId, 1, "completed", null);
        return runId;
    }

    private UUID insertRun(UserView owner, WorkflowView workflow, String status) {
        UUID runId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, error_message, "
                + "started_at, finished_at) VALUES (?, ?, ?, ?, 'in', ?, ?, ?)", runId, workflow.id(), owner.id(),
                status, "error".equals(status) ? "failed" : null, now, now);
        return runId;
    }

    private void insertStep(UUID runId, int order, String status, String errorCode) {
        Timestamp now = Timestamp.from(Instant.now());
        boolean completed = "completed".equals(status);
        jdbcTemplate.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, error_code, "
                + "error_message, started_at, finished_at) VALUES (?, ?, ?, 'echo', ?, 'in', ?, ?, ?, ?, ?)",
                UUID.randomUUID(), runId, order, status, completed ? "carried" : null, errorCode,
                errorCode == null ? null : "failed", now, now);
    }

    record RetryRunRequestView(Integer fromStepOrder, Boolean acknowledgeCost) {
    }
}
