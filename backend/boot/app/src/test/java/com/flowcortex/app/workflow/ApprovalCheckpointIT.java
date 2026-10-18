package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The approval checkpoint end to end over HTTP: an echo → APPROVAL_CHECKPOINT → echo run pauses
 * at the checkpoint with one pending review task, and the owner's decision resumes or ends it.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ApprovalCheckpointIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void approvingWithoutAPayloadResumesTheRunWithTheOriginalPayload() {
        UserView owner = newUser("approve-original");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner, token, "hello");

        ReviewTaskResponseView task = singlePendingTask(token, runId);
        assertThat(task.originalPayload()).isEqualTo("hello");

        ResponseEntity<ReviewTaskResponseView> approved =
                post("/api/v1/review-tasks/" + task.id() + "/approve", token, null, ReviewTaskResponseView.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().status()).isEqualTo("approved");

        RunView finished = awaitStatus(owner, runId, "completed");
        assertThat(finished.steps().get(2).output()).isEqualTo("hello");
    }

    @Test
    void approvingWithAReviewedPayloadCarriesItToTheNextStep() {
        UserView owner = newUser("approve-edited");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner, token, "hello");
        ReviewTaskResponseView task = singlePendingTask(token, runId);

        ResponseEntity<ReviewTaskResponseView> approved = post("/api/v1/review-tasks/" + task.id() + "/approve",
                token, new ApproveRequestView("edited"), ReviewTaskResponseView.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);

        RunView finished = awaitStatus(owner, runId, "completed");
        assertThat(finished.steps().get(2).output()).isEqualTo("edited");
    }

    @Test
    void rejectingEndsTheRunWithTheCheckpointStepInError() {
        UserView owner = newUser("reject");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner, token, "hello");
        ReviewTaskResponseView task = singlePendingTask(token, runId);

        ResponseEntity<ReviewTaskResponseView> rejected =
                post("/api/v1/review-tasks/" + task.id() + "/reject", token, null, ReviewTaskResponseView.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody().status()).isEqualTo("rejected");

        RunView finished = workflowRunService.getRun(owner.id(), runId);
        assertThat(finished.status()).isEqualTo("error");
        assertThat(finished.steps().get(1).status()).isEqualTo("error");
        assertThat(finished.steps().get(1).errorCode()).isEqualTo("APPROVAL_REJECTED");
        assertThat(finished.steps().get(2).status()).isEqualTo("pending");
    }

    @Test
    void aRunStartedWithNoInputStillPausesAtTheCheckpoint() {
        UserView owner = newUser("null-input");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner, token, null);

        ReviewTaskResponseView task = singlePendingTask(token, runId);
        assertThat(task.originalPayload()).isNull();
    }

    @Test
    void aSecondDecisionOnTheSameTaskIsAConflict() {
        UserView owner = newUser("second-decision");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner, token, "hello");
        ReviewTaskResponseView task = singlePendingTask(token, runId);

        assertThat(post("/api/v1/review-tasks/" + task.id() + "/approve", token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(post("/api/v1/review-tasks/" + task.id() + "/approve", token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/api/v1/review-tasks/" + task.id() + "/reject", token, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anotherMembersDecisionIsForbiddenAndTheirRunStaysHidden() {
        UserView owner = newUser("task-owner");
        UserView stranger = newUser("task-stranger");
        String ownerToken = loginAs(owner);
        String strangerToken = loginAs(stranger);
        UUID runId = startCheckpointRun(owner, ownerToken, "hello");
        ReviewTaskResponseView task = singlePendingTask(ownerToken, runId);

        assertThat(get("/api/v1/runs/" + runId + "/review-tasks", strangerToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        for (String decision : new String[] {"approve", "reject"}) {
            ResponseEntity<ProblemView> refused = post("/api/v1/review-tasks/" + task.id() + "/" + decision,
                    strangerToken, null, ProblemView.class);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody().detail()).isEqualTo("You can't decide on this run");
        }
        assertThat(singlePendingTask(ownerToken, runId).status()).isEqualTo("pending");
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("waiting_approval");
    }

    @Test
    void approvingWithABlankEditIsUnprocessableAndTheStepKeepsWaiting() {
        UserView owner = newUser("approve-blank");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner, token, "hello");
        ReviewTaskResponseView task = singlePendingTask(token, runId);

        ResponseEntity<ProblemView> refused = post("/api/v1/review-tasks/" + task.id() + "/approve", token,
                new ApproveRequestView("   "), ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().errors()).containsKey("reviewedPayload");
        assertThat(singlePendingTask(token, runId).status()).isEqualTo("pending");
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("waiting_approval");
    }

    @Test
    void aDecisionForAnUnknownTaskIsNotFound() {
        String token = loginAs(newUser("unknown-task"));

        ResponseEntity<ProblemView> missing = post("/api/v1/review-tasks/" + UUID.randomUUID() + "/approve", token,
                null, ProblemView.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().detail()).isEqualTo("Review task not found");
    }

    @Test
    void aDecisionOnAStoppedRunIsUnprocessableAndTheRunStaysCancelled() {
        UserView owner = newUser("approve-cancelled");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner, token, "hello");
        ReviewTaskResponseView task = singlePendingTask(token, runId);
        jdbcTemplate.update("UPDATE workflow_run SET status = 'cancelled' WHERE id = ?", runId);

        ResponseEntity<ProblemView> refused = post("/api/v1/review-tasks/" + task.id() + "/approve", token, null,
                ProblemView.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().detail()).isEqualTo("This run is being stopped");
        assertThat(refused.getBody().code()).isEqualTo("RUN_STOPPING");
        assertThat(singlePendingTask(token, runId).status()).isEqualTo("pending");
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("cancelled");
    }

    @Test
    void anApproveAndARejectSentTogetherRecordExactlyOne() throws Exception {
        UserView owner = newUser("decision-race");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner, token, "hello");
        ReviewTaskResponseView task = singlePendingTask(token, runId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<ProblemView>> approve = pool.submit(() -> {
                start.await();
                return post("/api/v1/review-tasks/" + task.id() + "/approve", token, null, ProblemView.class);
            });
            Future<ResponseEntity<ProblemView>> reject = pool.submit(() -> {
                start.await();
                return post("/api/v1/review-tasks/" + task.id() + "/reject", token, null, ProblemView.class);
            });
            start.countDown();
            ResponseEntity<ProblemView> approved = approve.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            ResponseEntity<ProblemView> rejected = reject.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            List<ResponseEntity<ProblemView>> answers = List.of(approved, rejected);
            assertThat(answers).extracting(ResponseEntity::getStatusCode)
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            ResponseEntity<ProblemView> loser = answers.stream()
                    .filter(answer -> HttpStatus.CONFLICT.equals(answer.getStatusCode())).findFirst().orElseThrow();
            assertThat(loser.getBody().detail()).isEqualTo("This step is no longer waiting for review");

            if (HttpStatus.OK.equals(approved.getStatusCode())) {
                RunView finished = awaitStatus(owner, runId, "completed");
                assertThat(finished.steps().get(1).status()).isEqualTo("completed");
                assertThat(finished.steps().get(2).output()).isEqualTo("hello");
            } else {
                RunView finished = workflowRunService.getRun(owner.id(), runId);
                assertThat(finished.status()).isEqualTo("error");
                assertThat(finished.steps().get(1).errorCode()).isEqualTo("APPROVAL_REJECTED");
                assertThat(finished.steps().get(2).status()).isEqualTo("pending");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Starts an echo → APPROVAL_CHECKPOINT → echo run over HTTP and waits until it pauses. */
    private UUID startCheckpointRun(UserView owner, String token, String input) {
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("review", "APPROVAL_CHECKPOINT", null),
                new NewWorkflowStep("publish", "echo", null));
        ResponseEntity<RunResponseView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRunRequestView(input), RunResponseView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID runId = started.getBody().id();
        awaitStatus(owner, runId, "waiting_approval");
        return runId;
    }

    private ReviewTaskResponseView singlePendingTask(String token, UUID runId) {
        ResponseEntity<ReviewTaskResponseView[]> tasks =
                get("/api/v1/runs/" + runId + "/review-tasks", token, ReviewTaskResponseView[].class);
        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tasks.getBody()).hasSize(1);
        ReviewTaskResponseView task = tasks.getBody()[0];
        assertThat(task.status()).isEqualTo("pending");
        assertThat(task.reviewType()).isEqualTo("APPROVAL_CHECKPOINT");
        return task;
    }

    private RunView awaitStatus(UserView owner, UUID runId, String status) {
        return pollUntil(owner.id(), runId, run -> status.equals(run.status()), TIMEOUT);
    }

    record ApproveRequestView(String reviewedPayload) {
    }

    record ProblemView(String detail, String code, Map<String, String> errors) {
    }

    record ReviewTaskResponseView(
            UUID id, UUID runId, UUID runStepId, String reviewType, String status, String originalPayload,
            String reviewedPayload, UUID reviewedBy, Instant reviewedAt, Instant createdAt) {
    }
}
