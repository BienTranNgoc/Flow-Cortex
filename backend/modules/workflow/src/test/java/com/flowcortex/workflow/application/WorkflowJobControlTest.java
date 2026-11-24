package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaControlResponse;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowJobControlTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunStepRepository runSteps = mock(RunStepRepository.class);
    private final WorkflowJobControl control = new WorkflowJobControl(runs, runSteps);

    private final UUID runId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private RunStep step(int order, String type, String inputRef) {
        return new RunStep(UUID.randomUUID(), runId, (short) order, type, null, null, inputRef);
    }

    private List<RunStep> parallelStepsInFlight() {
        RunStep s1 = step(1, "generate_script", null);
        s1.markRunning(NOW);
        s1.markCompleted(null, "{}", NOW);
        RunStep s2 = step(2, "APPROVAL_CHECKPOINT", null);
        s2.markRunning(NOW);
        s2.markCompleted(null, "{}", NOW);
        RunStep s3 = step(3, "generate_storyboard", "{step:2}");
        s3.markRunning(NOW);
        s3.markCompleted(null, "{}", NOW);
        RunStep s4 = step(4, "generate_speech", "{step:2.script}");
        s4.markRunning(NOW);
        s4.markCompleted(null, "{}", NOW);
        RunStep s5 = step(5, "generate_video", "{step:3}");
        s5.markRunning(NOW);
        RunStep s6 = step(6, "generate_video", "{step:3}");
        s6.markRunning(NOW);
        RunStep s7 = step(7, "render_video", null);
        return List.of(s1, s2, s3, s4, s5, s6, s7);
    }

    @Test
    void returnsContinueWhenStepIsRunningAndSiblingHasNotFailed() {
        WorkflowRun run = new WorkflowRun(runId, ProductVideoWorkflow.ID, ownerId, "{}", NOW);
        List<RunStep> steps = parallelStepsInFlight();
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runSteps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(steps);

        assertThat(control.decide(runId, steps.get(4).getId(), 1)).isEqualTo(MediaControlResponse.Decision.CONTINUE);
        assertThat(control.decide(runId, steps.get(5).getId(), 1)).isEqualTo(MediaControlResponse.Decision.CONTINUE);
    }

    @Test
    void returnsCancelWhenSiblingClipFailedEvenIfRunIsCancelling() {
        WorkflowRun run = new WorkflowRun(runId, ProductVideoWorkflow.ID, ownerId, "{}", NOW);
        List<RunStep> steps = parallelStepsInFlight();
        steps.get(4).markError(null, "provider_request_failed", "boom", NOW);
        run.markError("boom", NOW);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runSteps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(steps);

        assertThat(control.decide(runId, steps.get(5).getId(), 1))
                .isEqualTo(MediaControlResponse.Decision.CANCEL_SIBLING_FAILED);
        assertThat(control.decide(runId, steps.get(4).getId(), 1))
                .isEqualTo(MediaControlResponse.Decision.DISCARD_STALE);
    }

    @Test
    void returnsDiscardStaleForUnknownJobWrongAttemptOrClosedStep() {
        WorkflowRun run = new WorkflowRun(runId, ProductVideoWorkflow.ID, ownerId, "{}", NOW);
        List<RunStep> steps = parallelStepsInFlight();
        steps.get(5).markCompleted(null, "{}", NOW);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(runSteps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(steps);

        assertThat(control.decide(UUID.randomUUID(), steps.get(4).getId(), 1))
                .isEqualTo(MediaControlResponse.Decision.DISCARD_STALE);
        assertThat(control.decide(runId, UUID.randomUUID(), 1))
                .isEqualTo(MediaControlResponse.Decision.DISCARD_STALE);
        assertThat(control.decide(runId, steps.get(4).getId(), 2))
                .isEqualTo(MediaControlResponse.Decision.DISCARD_STALE);
        assertThat(control.decide(runId, steps.get(5).getId(), 1))
                .isEqualTo(MediaControlResponse.Decision.DISCARD_STALE);
        assertThat(control.decide(runId, steps.get(6).getId(), 1))
                .isEqualTo(MediaControlResponse.Decision.DISCARD_STALE);
    }
}
