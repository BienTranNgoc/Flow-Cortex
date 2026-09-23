package com.flowcortex.app.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserView;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The workflow run engine end to end, exercised through the real {@code
 * api} ports.
 */
@Testcontainers
@SpringBootTest
class WorkflowRunEngineIT extends AbstractWorkflowModuleIT {

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
    void danglingInputRefHaltsTheRunWithErrorOnThatStepAndDownstreamStaysPending() {
        UserView user = newUser("dangling-ref-user");
        WorkflowView workflow = newWorkflow(user.id(), false,
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("dangling", "echo", "{step:9.field}"),
                new NewWorkflowStep("never-reached", "echo", null));

        RunView started = workflowRunService.startRun(user.id(), workflow.id(), "hello");
        RunView finished = awaitTerminal(user.id(), started.id());

        assertThat(finished.status()).isEqualTo("error");
        List<RunStepView> steps = finished.steps();
        assertThat(steps.get(0).status()).isEqualTo("completed");
        assertThat(steps.get(1).status()).isEqualTo("error");
        assertThat(steps.get(1).errorMessage()).contains("references step 9");
        assertThat(steps.get(2).status()).isEqualTo("pending");
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
