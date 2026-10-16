package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.media.api.MediaError;
import com.flowcortex.media.api.MediaJobCompleted;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaResult;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepOutputView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * {@code POST /api/v1/runs/{id}/cancel} with the cancel switch on. In-flight and paused states are
 * seeded with SQL and the worker's result is published as the media module would, so each case
 * controls exactly which step is running when the stop lands. The sweep is pushed out to an hour
 * so it never advances a seeded run between steps.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.workflow.cancel.enabled=true", "flowcortex.workflow.reaper-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class RunCancellationIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RunStepRepository runStepRepository;

    record ProblemView(int status, String detail) {
    }

    record OutputUrlView(String url, Instant expiresAt) {
    }

    @Test
    void stoppingDuringTheFirstStepSkipsTheRestAndEndsCancelledOnceThatStepCompletes() {
        UserView owner = newUser("cancel-in-flight");
        String token = loginAs(owner);
        UUID runId = seedRun(owner, "running", "running", "pending", "pending");

        ResponseEntity<RunResponseView> response = cancel(runId, token, RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("cancelling");
        assertThat(response.getBody().steps()).extracting(RunStepResponseView::status)
                .containsExactly("running", "skipped", "skipped");

        UUID stepId = response.getBody().steps().get(0).id();
        publishAndAwait(new MediaResult("1", stepId, runId, 1, 1, MediaResult.Status.COMPLETED,
                List.of(new MediaOutput("video-details.json", MEDIA_BUCKET,
                        "runs/" + runId + "/steps/1/attempts/1/video-details.json", "application/json", 42L, null)),
                Map.of("videoCodec", "h264"), null, Instant.now(), "worker-it"));

        RunView stopped = pollUntil(owner.id(), runId, run -> "cancelled".equals(run.status()), TIMEOUT);
        assertThat(stopped.finishedAt()).isNotNull();
        assertThat(stopped.steps()).extracting(step -> step.status()).containsExactly("completed", "skipped", "skipped");
        assertThat(stopped.steps().get(0).output()).isEqualTo("{\"videoCodec\":\"h264\"}");
        assertThat(stopped.steps().get(0).outputs())
                .containsExactly(new RunStepOutputView("video-details.json", "application/json", 42L));
        assertThat(get("/api/v1/runs/" + runId + "/steps/1/outputs/video-details.json/url", token,
                OutputUrlView.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void stoppingARunWaitingForApprovalCancelsItAtOnceAndSkipsTheApprovalStep() {
        UserView owner = newUser("cancel-waiting");
        String token = loginAs(owner);
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("review", "APPROVAL_CHECKPOINT", null),
                new NewWorkflowStep("publish", "echo", null));
        ResponseEntity<RunResponseView> started = post("/api/v1/workflows/" + workflow.id() + "/runs", token,
                new StartRunRequestView("hello"), RunResponseView.class);
        UUID runId = started.getBody().id();
        pollUntil(owner.id(), runId, run -> "waiting_approval".equals(run.status()), TIMEOUT);

        ResponseEntity<RunResponseView> response = cancel(runId, token, RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("cancelled");
        assertThat(response.getBody().finishedAt()).isNotNull();
        assertThat(response.getBody().steps()).extracting(RunStepResponseView::status)
                .containsExactly("completed", "skipped", "skipped");
    }

    @Test
    void stoppingBetweenStepsCancelsAtOnce() {
        UserView owner = newUser("cancel-between");
        String token = loginAs(owner);
        UUID runId = seedRun(owner, "running", "completed", "pending");

        ResponseEntity<RunResponseView> response = cancel(runId, token, RunResponseView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("cancelled");
        assertThat(response.getBody().steps()).extracting(RunStepResponseView::status)
                .containsExactly("completed", "skipped");
    }

    @Test
    void stoppingAgainWhileStoppingOrAfterTheStopAnswers200AndChangesNothing() {
        UserView owner = newUser("cancel-twice");
        String token = loginAs(owner);
        UUID stopping = seedRun(owner, "running", "running", "pending");
        UUID stopped = seedRun(owner, "running", "completed", "pending");
        RunResponseView firstStopping = cancel(stopping, token, RunResponseView.class).getBody();
        RunResponseView firstStopped = cancel(stopped, token, RunResponseView.class).getBody();

        ResponseEntity<RunResponseView> againStopping = cancel(stopping, token, RunResponseView.class);
        ResponseEntity<RunResponseView> againStopped = cancel(stopped, token, RunResponseView.class);

        assertThat(againStopping.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(againStopping.getBody()).isEqualTo(firstStopping);
        assertThat(againStopping.getBody().status()).isEqualTo("cancelling");
        assertThat(againStopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(againStopped.getBody()).isEqualTo(firstStopped);
        assertThat(againStopped.getBody().status()).isEqualTo("cancelled");
    }

    @Test
    void stoppingAFinishedRunIsAConflictAndChangesNothing() {
        UserView owner = newUser("cancel-finished");
        String token = loginAs(owner);

        for (String status : List.of("completed", "error")) {
            UUID runId = seedRun(owner, status, "completed", status);
            RunView before = workflowRunService.getRun(owner.id(), runId);

            ResponseEntity<ProblemView> response = cancel(runId, token, ProblemView.class);

            assertThat(response.getStatusCode()).as(status).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().detail()).as(status).isEqualTo("This run has already finished");
            assertThat(workflowRunService.getRun(owner.id(), runId)).as(status).isEqualTo(before);
        }
    }

    @Test
    void anotherMembersRunAndAnUnknownRunAreNotFound() {
        UserView owner = newUser("cancel-owner");
        UserView stranger = newUser("cancel-stranger");
        UUID runId = seedRun(owner, "running", "running", "pending");
        String strangerToken = loginAs(stranger);

        for (UUID id : List.of(runId, UUID.randomUUID())) {
            ResponseEntity<ProblemView> response = cancel(id, strangerToken, ProblemView.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().detail()).startsWith("Workflow run not found");
        }
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("running");
    }

    @Test
    void aResultArrivingForACancelledRunLeavesItCancelled() {
        UserView owner = newUser("cancel-late-result");
        String token = loginAs(owner);
        UUID runId = seedRun(owner, "running", "completed", "pending");
        RunResponseView cancelled = cancel(runId, token, RunResponseView.class).getBody();
        UUID skippedStepId = cancelled.steps().get(1).id();

        publishAndAwait(new MediaResult("1", skippedStepId, runId, 1, 1, MediaResult.Status.COMPLETED, List.of(),
                Map.of("videoCodec", "h264"), null, Instant.now(), "worker-it"));

        RunView after = workflowRunService.getRun(owner.id(), runId);
        assertThat(after.status()).isEqualTo("cancelled");
        assertThat(after.finishedAt()).isEqualTo(cancelled.finishedAt());
        assertThat(after.steps()).extracting(step -> step.status()).containsExactly("completed", "skipped");
    }

    @Test
    void aFailingInFlightStepEndsAStoppingRunCancelledNotError() {
        UserView owner = newUser("cancel-in-flight-fails");
        String token = loginAs(owner);
        UUID runId = seedRun(owner, "running", "running", "pending");
        RunResponseView stopping = cancel(runId, token, RunResponseView.class).getBody();
        UUID stepId = stopping.steps().get(0).id();

        publishAndAwait(new MediaResult("1", stepId, runId, 1, 1, MediaResult.Status.ERROR, null, null,
                new MediaError(MediaError.Code.PROBE_FAILED, "boom", false), Instant.now(), "worker-it"));

        RunView stopped = pollUntil(owner.id(), runId, run -> !"cancelling".equals(run.status()), TIMEOUT);
        assertThat(stopped.status()).isEqualTo("cancelled");
        assertThat(stopped.steps()).extracting(step -> step.status()).containsExactly("error", "skipped");
        assertThat(stopped.steps().get(0).errorCode()).isEqualTo("probe_failed");
    }

    @Test
    void aPendingStepCannotBeClaimedOnceTheRunIsNoLongerRunning() {
        UserView owner = newUser("cancel-claim");
        UUID runId = seedRun(owner, "cancelling", "completed", "pending");
        UUID pendingStepId = workflowRunService.getRun(owner.id(), runId).steps().get(1).id();

        Integer claimed = transactionTemplate.execute(status -> runStepRepository.claimPending(pendingStepId,
                Instant.now(), Instant.now().plusSeconds(600), "in"));

        assertThat(claimed).isZero();
        assertThat(workflowRunService.getRun(owner.id(), runId).steps().get(1).status()).isEqualTo("pending");
    }

    private <T> ResponseEntity<T> cancel(UUID runId, String token, Class<T> responseType) {
        return post("/api/v1/runs/" + runId + "/cancel", token, null, responseType);
    }

    private UUID seedRun(UserView owner, String runStatus, String... stepStatuses) {
        return seedRun(this, jdbcTemplate, owner, runStatus, stepStatuses);
    }

    /**
     * A run of a fresh workflow in {@code runStatus}, one {@code probe} step per entry of {@code
     * stepStatuses} in order; a running step is claimed and not yet picked up, well within its
     * queue deadline.
     */
    static UUID seedRun(AbstractWorkflowModuleIT test, JdbcTemplate jdbcTemplate, UserView owner, String runStatus,
            String... stepStatuses) {
        NewWorkflowStep[] definition = new NewWorkflowStep[stepStatuses.length];
        for (int i = 0; i < stepStatuses.length; i++) {
            definition[i] = new NewWorkflowStep("step-" + (i + 1), "probe", null);
        }
        WorkflowView workflow = test.newWorkflow(owner.id(), false, definition);
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
                    + "started_at, finished_at, queue_deadline) VALUES (?, ?, ?, 'probe', ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), runId, i + 1, status, started ? "in" : null,
                    "completed".equals(status) ? "{}" : null, started ? now : null, closed ? now : null,
                    "running".equals(status) ? Timestamp.from(Instant.now().plusSeconds(1800)) : null);
        }
        return runId;
    }

    /** Publishes a worker result and waits until every listener has handled it (completed publications are deleted). */
    private void publishAndAwait(MediaResult result) {
        transactionTemplate.executeWithoutResult(status -> events.publishEvent(new MediaJobCompleted(result)));
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (outstandingPublications(result.jobId()) > 0) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("The result for step " + result.jobId() + " was not handled within "
                        + TIMEOUT);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the result of step " + result.jobId(), e);
            }
        }
    }

    private long outstandingPublications(UUID jobId) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM event_publication "
                + "WHERE event_type LIKE '%MediaJobCompleted%' AND serialized_event LIKE ?", Long.class,
                "%" + jobId + "%");
        return count == null ? 0 : count;
    }
}

/** The cancel switch at its default (off): a dedicated context, since the property binds at startup. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "flowcortex.workflow.reaper-interval-ms=3600000")
@AutoConfigureTestRestTemplate
class RunCancellationSwitchedOffIT extends AbstractWorkflowHttpIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void stoppingIsRefusedWith503AndTheRunKeepsGoing() {
        UserView owner = newUser("cancel-off");
        String token = loginAs(owner);
        UUID runId = RunCancellationIT.seedRun(this, jdbcTemplate, owner, "running", "running", "pending");

        ResponseEntity<RunCancellationIT.ProblemView> response = post("/api/v1/runs/" + runId + "/cancel", token,
                null, RunCancellationIT.ProblemView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().detail()).isEqualTo("This feature is turned off");
        RunView after = workflowRunService.getRun(owner.id(), runId);
        assertThat(after.status()).isEqualTo("running");
        assertThat(after.steps()).extracting(step -> step.status()).containsExactly("running", "pending");
    }
}
