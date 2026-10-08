package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    void anotherUsersTaskIsNotFound() {
        UserView owner = newUser("task-owner");
        UserView stranger = newUser("task-stranger");
        String ownerToken = loginAs(owner);
        String strangerToken = loginAs(stranger);
        UUID runId = startCheckpointRun(owner, ownerToken, "hello");
        ReviewTaskResponseView task = singlePendingTask(ownerToken, runId);

        assertThat(get("/api/v1/runs/" + runId + "/review-tasks", strangerToken, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/review-tasks/" + task.id() + "/approve", strangerToken, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/review-tasks/" + task.id() + "/reject", strangerToken, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("waiting_approval");
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

    record ReviewTaskResponseView(
            UUID id, UUID runId, UUID runStepId, String reviewType, String status, String originalPayload,
            String reviewedPayload, UUID reviewedBy, Instant reviewedAt, Instant createdAt) {
    }
}
