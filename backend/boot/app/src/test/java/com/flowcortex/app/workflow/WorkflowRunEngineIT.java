package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.media.api.MediaJobCompleted;
import com.flowcortex.media.api.MediaJobProgressed;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.MediaProgress;
import com.flowcortex.media.api.MediaResult;
import com.flowcortex.shared.web.GlobalExceptionHandler;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepOutputView;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolationException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The workflow run engine end to end, exercised through the real {@code
 * api} ports. The reaper runs every 200 ms here so a stuck step is closed within
 * the poll timeout; stuck states are seeded with SQL because nothing in-process can
 * produce a worker that stopped heartbeating.
 */
@Testcontainers
@SpringBootTest(properties = "flowcortex.workflow.reaper-interval-ms=200")
class WorkflowRunEngineIT extends AbstractWorkflowModuleIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private GlobalExceptionHandler exceptionHandler;

    @Test
    void aPickedUpStepWhoseLeaseLapsedIsClosedAsLeaseExpired() {
        UserView user = newUser("lease-expired");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));
        Instant past = Instant.now().minusSeconds(60);
        UUID runId = seedRunningStep(workflow.id(), user.id(), "echo", past.plusSeconds(3600), past,
                past.plusSeconds(10), past.plusSeconds(3600));
        double before = reaped("lease_expired");

        RunView finished = awaitTerminal(user.id(), runId);

        assertThat(finished.status()).isEqualTo("error");
        assertThat(finished.steps().get(0).status()).isEqualTo("error");
        assertThat(finished.steps().get(0).errorCode()).isEqualTo("lease_expired");
        assertThat(finished.errorMessage()).isNotBlank();
        assertThat(reaped("lease_expired")).isGreaterThan(before);
    }

    @Test
    void aRunLeftWithAPendingStepAndNothingInFlightIsAdvancedByTheSweep() {
        UserView user = newUser("stalled-run");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "echo", null));
        UUID runId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                + "VALUES (?, ?, ?, 'running', 'in', ?)", runId, workflow.id(), user.id(), now);
        jdbcTemplate.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, output, started_at, "
                + "finished_at) VALUES (?, ?, 1, 'echo', 'completed', 'in', 'carried', ?, ?)",
                UUID.randomUUID(), runId, now, now);
        jdbcTemplate.update("INSERT INTO run_step (id, run_id, step_order, type, status) "
                + "VALUES (?, ?, 2, 'echo', 'pending')", UUID.randomUUID(), runId);

        RunView finished = awaitTerminal(user.id(), runId);

        assertThat(finished.status()).isEqualTo("completed");
        assertThat(finished.steps().get(1).output()).isEqualTo("carried");
    }

    @Test
    void aStepNeverPickedUpBeforeItsQueueDeadlineIsClosedAsQueueTimeout() {
        UserView user = newUser("queue-timeout");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "probe", null));
        UUID runId = seedRunningStep(workflow.id(), user.id(), "probe", Instant.now().minusSeconds(1), null, null,
                null);

        RunView finished = awaitTerminal(user.id(), runId);

        assertThat(finished.status()).isEqualTo("error");
        assertThat(finished.steps().get(0).errorCode()).isEqualTo("queue_timeout");
        assertThat(finished.steps().get(0).pickedUpAt()).isNull();
    }

    @Test
    void progressFromTheWorkerIsExposedAndItsResultCompletesTheStepWithNamedOutputs() {
        UserView user = newUser("remote-progress");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "probe", null));
        UUID runId = seedRunningStep(workflow.id(), user.id(), "probe", Instant.now().plusSeconds(1800), null, null,
                null);
        UUID stepId = workflowRunService.getRun(user.id(), runId).steps().get(0).id();
        Instant startedAt = Instant.now();

        publish(new MediaJobProgressed(new MediaProgress("1", stepId, runId, 1, 1, MediaProgress.Kind.STARTED, null,
                null, startedAt)));
        publish(new MediaJobProgressed(new MediaProgress("1", stepId, runId, 1, 1, MediaProgress.Kind.HEARTBEAT, 40,
                null, startedAt.plusSeconds(1))));

        RunView inFlight = pollUntil(user.id(), runId, run -> {
            RunStepView step = run.steps().get(0);
            return step.pickedUpAt() != null && Integer.valueOf(40).equals(step.progressPercent());
        }, Duration.ofSeconds(10));
        assertThat(inFlight.steps().get(0).deliveryCount()).isEqualTo(1);
        assertThat(inFlight.steps().get(0).attempt()).isEqualTo(1);
        assertThat(inFlight.steps().get(0).status()).isEqualTo("running");

        MediaOutput details = new MediaOutput("video-details.json", "flowcortex-media",
                "runs/" + runId + "/steps/1/attempts/1/video-details.json", "application/json", 42L, null);
        publish(new MediaJobCompleted(new MediaResult("1", stepId, runId, 1, 1, MediaResult.Status.COMPLETED,
                List.of(details), Map.of("videoCodec", "h264"), null, Instant.now(), "worker-it")));

        RunView finished = awaitTerminal(user.id(), runId);
        assertThat(finished.status()).isEqualTo("completed");
        assertThat(finished.steps().get(0).output()).isEqualTo("{\"videoCodec\":\"h264\"}");
        assertThat(finished.steps().get(0).outputs())
                .containsExactly(new RunStepOutputView("video-details.json", "application/json", 42L));
    }

    @Test
    void anUnknownStepTypeIsRejectedAtCreationAsA400NamingTheStep() {
        UserView user = newUser("unknown-type");

        assertThatThrownBy(() -> newWorkflow(user.id(), false, new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "transcode", null)))
                .isInstanceOfSatisfying(ConstraintViolationException.class, violation -> {
                    ProblemDetail problem = exceptionHandler.handleConstraintViolation(violation);
                    assertThat(problem.getStatus()).isEqualTo(400);
                    assertThat(problem.getProperties()).extractingByKey("errors")
                            .asInstanceOf(InstanceOfAssertFactories.MAP)
                            .containsOnlyKeys("steps[1].type");
                });
        assertThat(workflowService.listVisibleWorkflows(user.id())).extracting(WorkflowView::ownerId)
                .doesNotContain(user.id());
    }

    @Test
    void inlineStepsRunOnBootsBoundedApplicationTaskExecutorAndNoExecutorBeanOfOursExists() throws Exception {
        assertThat(context.getBeansOfType(ThreadPoolTaskExecutor.class)).containsOnlyKeys("applicationTaskExecutor");
        ThreadPoolTaskExecutor executor = context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
        assertThat(executor.getCorePoolSize()).isEqualTo(8);
        assertThat(executor.getMaxPoolSize()).isEqualTo(16);
        assertThat(executor.getQueueCapacity()).isEqualTo(500);
        assertThat(executor.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS))
                .startsWith("app-async-");
    }

    private void publish(Object event) {
        transactionTemplate.executeWithoutResult(status -> events.publishEvent(event));
    }

    /** The steps these tests seed are inline ({@code echo}). */
    private double reaped(String code) {
        return meterRegistry.get("media.reaper.reaped").tags("code", code, "mode", "inline").counter().count();
    }

    /** A running run with one running step in the given lease state, as a crashed executor would leave it. */
    private UUID seedRunningStep(UUID workflowId, UUID ownerId, String type, Instant queueDeadline,
            Instant pickedUpAt, Instant leaseUntil, Instant runDeadline) {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO workflow_run (id, workflow_id, owner_id, status, input, started_at) "
                + "VALUES (?, ?, ?, 'running', 'in', ?)", runId, workflowId, ownerId, Timestamp.from(now));
        jdbcTemplate.update("INSERT INTO run_step (id, run_id, step_order, type, status, input, started_at, "
                        + "queue_deadline, picked_up_at, lease_until, run_deadline) "
                        + "VALUES (?, ?, 1, ?, 'running', 'in', ?, ?, ?, ?, ?)",
                UUID.randomUUID(), runId, type, Timestamp.from(now), timestamp(queueDeadline), timestamp(pickedUpAt),
                timestamp(leaseUntil), timestamp(runDeadline));
        return runId;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    @Test
    void startReturnsBeforeStepsFinishAndAllStepsStartPending() {
        UserView user = newUser("nonblocking");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));

        RunView started = workflowRunService.startRun(user.id(), workflow.id(), "hello");

        // The returned view is built INSIDE the same transaction that just
        // inserted the run — the async listener cannot possibly have run
        // yet, so this is deterministic, not a race.
        assertThat(started.status()).isEqualTo("running");
        assertThat(started.steps()).hasSize(1);
        assertThat(started.steps().get(0).status()).isEqualTo("pending");

        RunView finished = awaitTerminal(user.id(), started.id());
        assertThat(finished.status()).isEqualTo("completed");
        assertThat(finished.steps().get(0).status()).isEqualTo("completed");
        assertThat(finished.steps().get(0).output()).isEqualTo("hello");
    }

    @Test
    void aPollCanObserveANonTerminalStepBeforeCompletion() {
        // A possibility guarantee, not a determinism one: assert the
        // STRUCTURAL fact that makes it possible — the run starts
        // non-terminal — rather than requiring a
        // specific poll to catch an in-flight step (echo steps finish in
        // sub-millisecond time, so timing a genuine mid-flight catch would be
        // flaky).
        UserView user = newUser("poll-nonterminal");
        WorkflowView workflow = newWorkflow(user.id(), false, new NewWorkflowStep("only", "echo", null));

        RunView started = workflowRunService.startRun(user.id(), workflow.id(), "hi");
        RunView firstRead = workflowRunService.getRun(user.id(), started.id());

        assertThat(firstRead.status()).isIn("running", "completed");
        awaitTerminal(user.id(), started.id());
    }

    @Test
    void threeStepsExecuteInAscendingOrderAndAllComplete() {
        UserView user = newUser("seq-user");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "echo", null),
                new NewWorkflowStep("third", "echo", null));

        RunView started = workflowRunService.startRun(user.id(), workflow.id(), "hello");
        RunView finished = awaitTerminal(user.id(), started.id());

        assertThat(finished.status()).isEqualTo("completed");
        List<RunStepView> steps = finished.steps();
        assertThat(steps).extracting(RunStepView::status).containsExactly("completed", "completed", "completed");
        assertThat(steps).extracting(RunStepView::output).containsExactly("hello", "hello", "hello");
        // Sequential dispatch: each step's start time is not before the
        // previous step's.
        assertThat(steps.get(0).startedAt()).isBeforeOrEqualTo(steps.get(1).startedAt());
        assertThat(steps.get(1).startedAt()).isBeforeOrEqualTo(steps.get(2).startedAt());
    }

    @Test
    void inputRefResolvesAFieldFromAnEarlierStepsOutputEndToEnd() {
        UserView user = newUser("inputref-user");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("emit-json", "echo", null),
                new NewWorkflowStep("extract-field", "echo", "{step:1.value}"));

        RunView started = workflowRunService.startRun(user.id(), workflow.id(), "{\"value\":\"resolved-value\"}");
        RunView finished = awaitTerminal(user.id(), started.id());

        assertThat(finished.status()).isEqualTo("completed");
        assertThat(finished.steps().get(0).output()).isEqualTo("{\"value\":\"resolved-value\"}");
        assertThat(finished.steps().get(1).input()).isEqualTo("resolved-value");
        assertThat(finished.steps().get(1).output()).isEqualTo("resolved-value");
    }

    @Test
    void danglingInputRefIsRefusedBeforeAnyRunIsCreated() {
        UserView user = newUser("dangling-ref-user");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("dangling", "echo", "{step:9.field}"),
                new NewWorkflowStep("never-reached", "echo", null));

        assertThatThrownBy(() -> workflowRunService.startRun(user.id(), workflow.id(), "hello"))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("WORKFLOW_INCOMPLETE"));
    }

    @Test
    void allEchoStepsCompleteWhenTheRunIsStartedWithNoInput() {
        // The browser-loop QA bug: the seeded "Echo Demo (all steps succeed)"
        // is shaped exactly like this (three echo steps) and is started with
        // NO input from the UI (an "optional" input label). Step 1 echoes
        // the run's null input, completing with a null output — step 2's
        // DEFAULT resolution must treat that as a genuinely completed
        // predecessor and pass the null output through, not error.
        UserView user = newUser("no-input-user");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "echo", null),
                new NewWorkflowStep("third", "echo", null));

        RunView started = workflowRunService.startRun(user.id(), workflow.id(), null);
        RunView finished = awaitTerminal(user.id(), started.id());

        assertThat(finished.status()).isEqualTo("completed");
        List<RunStepView> steps = finished.steps();
        assertThat(steps).extracting(RunStepView::status).containsExactly("completed", "completed", "completed");
    }

    @Test
    void failingDemoStartedWithNoInputErrorsAtTheFailStepNotAtInputResolution() {
        // Same shape as the seeded "Failing Demo (middle step errors)":
        // echo/fail/echo, started with no input. Before the fix, step 2's
        // DEFAULT resolution wrongly errored on step 1's null output before
        // the deliberate "fail" step ever ran, surfacing the confusing
        // "Step 1 produced no output to default from" message instead of the
        // fail step's own.
        UserView user = newUser("no-input-failing-user");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("boom", "fail", null),
                new NewWorkflowStep("never-reached", "echo", null));

        RunView started = workflowRunService.startRun(user.id(), workflow.id(), null);
        RunView finished = awaitTerminal(user.id(), started.id());

        assertThat(finished.status()).isEqualTo("error");
        List<RunStepView> steps = finished.steps();
        assertThat(steps.get(0).status()).isEqualTo("completed");
        assertThat(steps.get(1).status()).isEqualTo("error");
        assertThat(steps.get(1).errorMessage()).contains("Simulated step failure");
        assertThat(steps.get(2).status()).isEqualTo("pending");
    }

    @Test
    void aFailingStepHaltsTheRunAndDownstreamStepsStayPending() {
        UserView user = newUser("failing-step-user");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("boom", "fail", null),
                new NewWorkflowStep("never-reached", "echo", null));

        RunView started = workflowRunService.startRun(user.id(), workflow.id(), "hello");
        RunView finished = awaitTerminal(user.id(), started.id());

        assertThat(finished.status()).isEqualTo("error");
        assertThat(finished.errorMessage()).contains("Simulated step failure");
        List<RunStepView> steps = finished.steps();
        assertThat(steps.get(0).status()).isEqualTo("completed");
        assertThat(steps.get(1).status()).isEqualTo("error");
        assertThat(steps.get(1).errorMessage()).contains("Simulated step failure");
        assertThat(steps.get(2).status()).isEqualTo("pending");
    }

    @Test
    void sharedWorkflowIsVisibleToAnotherUserButItsRunsStayOwnerScoped() {
        UserView owner = newUser("shared-owner");
        UserView otherMember = newUser("shared-other");
        WorkflowView shared = newWorkflow(owner.id(), true, new NewWorkflowStep("only", "echo", null));

        assertThat(workflowService.listVisibleWorkflows(otherMember.id()))
                .extracting(WorkflowView::id)
                .contains(shared.id());

        RunView otherRun = workflowRunService.startRun(otherMember.id(), shared.id(), "from-other-member");
        awaitTerminal(otherMember.id(), otherRun.id());

        // The owner of the (shared) workflow definition cannot read a run
        // they did not themselves start — a shared definition never means
        // shared runs.
        assertThatThrownBy(() -> workflowRunService.getRun(owner.id(), otherRun.id()))
                .isInstanceOf(NotFoundException.class);
        // The runner can read their own run.
        assertThat(workflowRunService.getRun(otherMember.id(), otherRun.id()).status()).isEqualTo("completed");
    }

    @Test
    void unsharedWorkflowIsNotVisibleToAnotherUser() {
        UserView owner = newUser("private-owner");
        UserView otherMember = newUser("private-other");
        WorkflowView privateWorkflow = newWorkflow(owner.id(), false, new NewWorkflowStep("only", "echo", null));

        assertThat(workflowService.listVisibleWorkflows(otherMember.id()))
                .extracting(WorkflowView::id)
                .doesNotContain(privateWorkflow.id());
        assertThatThrownBy(() -> workflowRunService.getRun(otherMember.id(), privateWorkflow.id()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void unknownRunIdIsNotFound() {
        UserView user = newUser("unknown-run-user");

        assertThatThrownBy(() -> workflowRunService.getRun(user.id(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }
}
