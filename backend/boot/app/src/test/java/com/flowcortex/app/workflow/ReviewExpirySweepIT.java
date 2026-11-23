package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.ReviewExpirySweep;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
 * The expiry sweep over a real database, called directly: the scheduled tick is pushed an hour
 * out so only the call a test makes runs. Runs that wait at a review are real; a task's overdue
 * time and age are moved with SQL because the sweep reads the stored columns.
 */
@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "flowcortex.workflow.reaper-interval-ms=3600000",
        "flowcortex.workflow.review.sweep-interval-ms=3600000"})
@AutoConfigureTestRestTemplate
class ReviewExpirySweepIT extends AbstractWorkflowHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ReviewExpirySweep sweep;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewTaskRepository reviewTasks;

    @Autowired
    private WorkflowRepository workflows;

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private RunStepRepository steps;

    private final List<UUID> createdTasks = new ArrayList<>();

    @AfterEach
    void deleteDirectlyCreatedTasks() {
        for (UUID id : createdTasks) {
            jdbc.update("DELETE FROM review_task WHERE id = ?", id);
        }
        createdTasks.clear();
    }

    @Test
    void overdueWaitingRunEndsInErrorReviewExpired() {
        UserView owner = newUser("sweep-overdue");
        UUID runId = startCheckpointRun(owner);
        makeOverdue(runId);

        sweep.sweep();

        assertThat(taskStatus(runId)).isEqualTo("expired");
        RunView run = workflowRunService.getRun(owner.id(), runId);
        assertThat(run.status()).isEqualTo("error");
        assertThat(run.errorMessage()).isEqualTo("No one decided in time, so the run stopped.");
        assertThat(run.steps().get(1).status()).isEqualTo("error");
        assertThat(run.steps().get(1).errorCode()).isEqualTo("REVIEW_EXPIRED");
        assertThat(run.steps().get(1).errorMessage()).isEqualTo("No one decided in time, so the run stopped.");
        assertThat(run.steps().get(2).status()).isEqualTo("pending");
    }

    @Test
    void overdueTaskOfACancelledRunExpiresAndTheRunStays() {
        UserView owner = newUser("sweep-cancelled");
        UUID runId = startCheckpointRun(owner);
        jdbc.update("UPDATE workflow_run SET status = 'cancelled' WHERE id = ?", runId);
        makeOverdue(runId);

        sweep.sweep();

        assertThat(taskStatus(runId)).isEqualTo("expired");
        RunView run = workflowRunService.getRun(owner.id(), runId);
        assertThat(run.status()).isEqualTo("cancelled");
        assertThat(run.steps().get(1).errorCode()).isNull();
    }

    @Test
    void notYetDueTaskIsUntouched() {
        UserView owner = newUser("sweep-not-due");
        UUID runId = startCheckpointRun(owner);

        sweep.sweep();

        assertThat(taskStatus(runId)).isEqualTo("pending");
        assertThat(workflowRunService.getRun(owner.id(), runId).status()).isEqualTo("waiting_approval");
    }

    @Test
    void twoSweepsInParallelEndTheTaskOnce() throws Exception {
        UserView owner = newUser("sweep-parallel");
        UUID runId = startCheckpointRun(owner);
        makeOverdue(runId);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return sweep.sweep();
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return sweep.sweep();
            });
            start.countDown();

            int ended = first.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS) + second.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertThat(ended).isLessThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(taskStatus(runId)).isEqualTo("expired");
        RunView run = workflowRunService.getRun(owner.id(), runId);
        assertThat(run.status()).isEqualTo("error");
        assertThat(run.steps().get(1).errorCode()).isEqualTo("REVIEW_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT version FROM review_task WHERE run_id = ?", Long.class, runId))
                .isEqualTo(1L);
    }

    @Test
    void aDecisionCommittedFirstLeavesNothingToSweep() {
        UserView owner = newUser("sweep-decided");
        String token = loginAs(owner);
        UUID runId = startCheckpointRun(owner);
        UUID taskId = taskId(runId);
        ResponseEntity<String> approved = post("/api/v1/review-tasks/" + taskId + "/approve", token, null,
                String.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE review_task SET expires_at = now() - interval '1 minute' WHERE id = ?", taskId);

        assertThat(sweep.sweep()).isZero();

        assertThat(taskStatus(runId)).isEqualTo("approved");
        RunView run = pollUntil(owner.id(), runId, view -> "completed".equals(view.status()), TIMEOUT);
        assertThat(run.steps().get(1).errorCode()).isNull();
    }

    @Test
    void aQaTaskStillGeneratingAfterTheTimeoutGetsTheStandardQuestions() throws IOException {
        ReviewTask stale = generatingTask(Instant.now().minus(Duration.ofMinutes(3)));
        long versionBefore = reviewTasks.findById(stale.getId()).orElseThrow().getVersion();

        sweep.sweep();

        ReviewTask after = reviewTasks.findById(stale.getId()).orElseThrow();
        assertThat(after.getQuestionsState()).isEqualTo(ReviewTask.QUESTIONS_READY);
        assertThat(JSON.readTree(after.getQuestions())).isEqualTo(JSON.readTree("{\"standard\":true}"));
        assertThat(after.getVersion()).isGreaterThan(versionBefore);
    }

    @Test
    void aGeneratingTaskInsideTheTimeoutIsUntouched() {
        ReviewTask fresh = generatingTask(Instant.now().minus(Duration.ofSeconds(30)));

        sweep.sweep();

        ReviewTask after = reviewTasks.findById(fresh.getId()).orElseThrow();
        assertThat(after.getQuestionsState()).isEqualTo(ReviewTask.QUESTIONS_GENERATING);
        assertThat(after.getQuestions()).isNull();
    }

    private UUID startCheckpointRun(UserView owner) {
        WorkflowView workflow = newWorkflow(owner.id(), false,
                new NewWorkflowStep("draft", "echo", null),
                new NewWorkflowStep("review", "APPROVAL_CHECKPOINT", null),
                new NewWorkflowStep("publish", "echo", null));
        UUID runId = workflowRunService.startRun(owner.id(), workflow.id(), "hello").id();
        pollUntil(owner.id(), runId, run -> "waiting_approval".equals(run.status()), TIMEOUT);
        return runId;
    }

    private void makeOverdue(UUID runId) {
        jdbc.update("UPDATE review_task SET expires_at = now() - interval '1 minute' WHERE run_id = ?", runId);
    }

    private UUID taskId(UUID runId) {
        return jdbc.queryForObject("SELECT id FROM review_task WHERE run_id = ?", UUID.class, runId);
    }

    private String taskStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM review_task WHERE run_id = ?", String.class, runId);
    }

    private ReviewTask generatingTask(Instant createdAt) {
        Instant created = createdAt.truncatedTo(ChronoUnit.MICROS);
        Workflow workflow = workflows.saveAndFlush(
                new Workflow(UUID.randomUUID(), UUID.randomUUID(), "it-" + UUID.randomUUID(), false));
        WorkflowRun run = runs.saveAndFlush(
                new WorkflowRun(UUID.randomUUID(), workflow.getId(), UUID.randomUUID(), "in", created));
        RunStep step = steps.saveAndFlush(new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "qa.review"));
        UUID id = UUID.randomUUID();
        createdTasks.add(id);
        return reviewTasks.saveAndFlush(new ReviewTask(id, run.getId(), step.getId(), "QA_REVIEW", "payload", created,
                created.plus(Duration.ofDays(7)), ReviewTask.QUESTIONS_GENERATING));
    }
}
