package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReadyStepSelectorTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

    private final UUID runId = UUID.randomUUID();

    private RunStep step(int order, String type, String inputRef) {
        return new RunStep(UUID.randomUUID(), runId, (short) order, type, null, null, inputRef);
    }

    private static RunStep complete(RunStep step) {
        step.markRunning(NOW);
        step.markCompleted(null, "{}", NOW);
        return step;
    }

    private static RunStep running(RunStep step) {
        step.markRunning(NOW);
        return step;
    }

    private static RunStep errored(RunStep step) {
        step.markRunning(NOW);
        step.markError(null, "provider_request_failed", "failed", NOW);
        return step;
    }

    private List<RunStep> productVideoSteps() {
        return List.of(
                step(1, "generate_script", null),
                step(2, "APPROVAL_CHECKPOINT", null),
                step(3, "generate_storyboard", "{step:2}"),
                step(4, "generate_speech", "{step:2.script}"),
                step(5, "generate_video", "{step:3}"),
                step(6, "generate_video", "{step:3}"),
                step(7, "render_video", null));
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"WAITING_APPROVAL", "COMPLETED", "ERROR", "CANCELLING", "CANCELLED"})
    void blocksAnyNonRunningRunFirst(RunStatus status) {
        List<RunStep> steps = productVideoSteps();
        for (int i = 0; i < 4; i++) {
            complete(steps.get(i));
        }

        ReadyStepSelector.Decision decision = ReadyStepSelector.select(true, ProductVideoWorkflow.ID, status, steps);

        assertThat(decision).isInstanceOf(ReadyStepSelector.Decision.Halt.class);
    }

    @Test
    void dispatchesClipsFiveAndSixTogetherOncePrerequisitesComplete() {
        List<RunStep> steps = productVideoSteps();
        for (int i = 0; i < 4; i++) {
            complete(steps.get(i));
        }

        ReadyStepSelector.Decision decision =
                ReadyStepSelector.select(true, ProductVideoWorkflow.ID, RunStatus.RUNNING, steps);

        assertThat(decision).isInstanceOfSatisfying(ReadyStepSelector.Decision.Dispatch.class,
                dispatch -> assertThat(dispatch.steps()).containsExactly(steps.get(4), steps.get(5)));
    }

    @Test
    void recoversPendingClipWhenSiblingIsAlreadyRunning() {
        List<RunStep> withFiveRunning = productVideoSteps();
        for (int i = 0; i < 4; i++) {
            complete(withFiveRunning.get(i));
        }
        running(withFiveRunning.get(4));

        assertThat(ReadyStepSelector.select(true, ProductVideoWorkflow.ID, RunStatus.RUNNING, withFiveRunning))
                .isInstanceOfSatisfying(ReadyStepSelector.Decision.Dispatch.class,
                        dispatch -> assertThat(dispatch.steps()).containsExactly(withFiveRunning.get(5)));

        List<RunStep> withSixRunning = productVideoSteps();
        for (int i = 0; i < 4; i++) {
            complete(withSixRunning.get(i));
        }
        running(withSixRunning.get(5));

        assertThat(ReadyStepSelector.select(true, ProductVideoWorkflow.ID, RunStatus.RUNNING, withSixRunning))
                .isInstanceOfSatisfying(ReadyStepSelector.Decision.Dispatch.class,
                        dispatch -> assertThat(dispatch.steps()).containsExactly(withSixRunning.get(4)));
    }

    @Test
    void waitsForBothClipsBeforeAllowingRenderStep() {
        List<RunStep> steps = productVideoSteps();
        for (int i = 0; i < 4; i++) {
            complete(steps.get(i));
        }
        complete(steps.get(5));
        running(steps.get(4));

        assertThat(ReadyStepSelector.select(true, ProductVideoWorkflow.ID, RunStatus.RUNNING, steps))
                .isInstanceOf(ReadyStepSelector.Decision.Wait.class);

        steps.get(4).markCompleted(null, "{}", NOW);
        assertThat(ReadyStepSelector.select(true, ProductVideoWorkflow.ID, RunStatus.RUNNING, steps))
                .isInstanceOfSatisfying(ReadyStepSelector.Decision.Dispatch.class,
                        dispatch -> assertThat(dispatch.steps()).containsExactly(steps.get(6)));
    }

    @Test
    void haltsClipWindowWhenEitherClipFailed() {
        List<RunStep> steps = productVideoSteps();
        for (int i = 0; i < 4; i++) {
            complete(steps.get(i));
        }
        errored(steps.get(4));

        assertThat(ReadyStepSelector.select(true, ProductVideoWorkflow.ID, RunStatus.RUNNING, steps))
                .isInstanceOf(ReadyStepSelector.Decision.Halt.class);
    }

    @Test
    void staysSequentialWhenFlagDisabledOrWorkflowOrShapeDiffers() {
        List<RunStep> pvReady = productVideoSteps();
        for (int i = 0; i < 4; i++) {
            complete(pvReady.get(i));
        }

        assertThat(ReadyStepSelector.select(false, ProductVideoWorkflow.ID, RunStatus.RUNNING, pvReady))
                .isInstanceOfSatisfying(ReadyStepSelector.Decision.Dispatch.class,
                        d -> assertThat(d.steps()).containsExactly(pvReady.get(4)));

        assertThat(ReadyStepSelector.select(true, UUID.randomUUID(), RunStatus.RUNNING, pvReady))
                .isInstanceOfSatisfying(ReadyStepSelector.Decision.Dispatch.class,
                        d -> assertThat(d.steps()).containsExactly(pvReady.get(4)));

        List<RunStep> wrongRef = List.of(
                complete(step(1, "generate_script", null)),
                complete(step(2, "APPROVAL_CHECKPOINT", null)),
                complete(step(3, "generate_storyboard", "{step:2}")),
                complete(step(4, "generate_speech", "{step:2.script}")),
                step(5, "generate_video", "{step:3}"),
                step(6, "generate_video", "{step:2}"),
                step(7, "render_video", null));
        assertThat(ReadyStepSelector.select(true, ProductVideoWorkflow.ID, RunStatus.RUNNING, wrongRef))
                .isInstanceOfSatisfying(ReadyStepSelector.Decision.Dispatch.class,
                        d -> assertThat(d.steps()).containsExactly(wrongRef.get(4)));

        List<RunStep> beforeSpeechDone = productVideoSteps();
        complete(beforeSpeechDone.get(0));
        complete(beforeSpeechDone.get(1));
        complete(beforeSpeechDone.get(2));
        assertThat(ReadyStepSelector.select(true, ProductVideoWorkflow.ID, RunStatus.RUNNING, beforeSpeechDone))
                .isInstanceOfSatisfying(ReadyStepSelector.Decision.Dispatch.class,
                        d -> assertThat(d.steps()).containsExactly(beforeSpeechDone.get(3)));
    }
}
