package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductVideoRedoRulesTest {

    private static final Instant NOW = Instant.now();

    @Test
    void expandsMcScene1RedoToIncludeLipsyncAndRenderWhileReusingScene2() {
        List<RunStep> steps = completedMcSteps();
        ProductVideoRedoRules.Plan plan = ProductVideoRedoRules.resolve(
                ProductVideoWorkflow.MC_ID, RunStatus.COMPLETED, steps, List.of(7));

        assertThat(plan.rerunOrders()).containsExactlyInAnyOrder(7, 9, 10);
        assertThat(plan.reusedSteps()).extracting(RunStep::getStepOrder)
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5, (short) 6, (short) 8);
    }

    @Test
    void expandsMcScene2RedoToOnlyScene2AndRenderReusingLipsyncedScene1() {
        List<RunStep> steps = completedMcSteps();
        ProductVideoRedoRules.Plan plan = ProductVideoRedoRules.resolve(
                ProductVideoWorkflow.MC_ID, RunStatus.COMPLETED, steps, List.of(8));

        assertThat(plan.rerunOrders()).containsExactlyInAnyOrder(8, 10);
        assertThat(plan.reusedSteps()).extracting(RunStep::getStepOrder)
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5, (short) 6, (short) 7, (short) 9);
    }

    @Test
    void expandsMcLipsyncRedoToOnlyLipsyncAndRender() {
        List<RunStep> steps = completedMcSteps();
        ProductVideoRedoRules.Plan plan = ProductVideoRedoRules.resolve(
                ProductVideoWorkflow.MC_ID, RunStatus.COMPLETED, steps, List.of(9));

        assertThat(plan.rerunOrders()).containsExactlyInAnyOrder(9, 10);
        assertThat(plan.reusedSteps()).hasSize(8);
    }

    @Test
    void expandsShowroomScene1RedoToOnlyScene1AndRender() {
        UUID runId = UUID.randomUUID();
        List<RunStep> steps = List.of(
                completed(runId, 1, "generate_script", null),
                completed(runId, 2, HumanSteps.APPROVAL_CHECKPOINT, null),
                completed(runId, 3, "generate_storyboard", null),
                completed(runId, 4, "generate_speech", null),
                completed(runId, 5, "generate_video", null),
                completed(runId, 6, "generate_video", null),
                completed(runId, 7, "render_video", null));

        ProductVideoRedoRules.Plan plan = ProductVideoRedoRules.resolve(
                ProductVideoWorkflow.ID, RunStatus.COMPLETED, steps, List.of(5));

        assertThat(plan.rerunOrders()).containsExactlyInAnyOrder(5, 7);
        assertThat(plan.reusedSteps()).extracting(RunStep::getStepOrder)
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 6);
    }

    @Test
    void rejectsNonRedoableStepOrRunningRun() {
        List<RunStep> steps = completedMcSteps();
        assertThatThrownBy(() -> ProductVideoRedoRules.resolve(
                ProductVideoWorkflow.MC_ID, RunStatus.COMPLETED, steps, List.of(3)))
                .isInstanceOf(LocalizedStatusException.class);
        assertThatThrownBy(() -> ProductVideoRedoRules.resolve(
                ProductVideoWorkflow.MC_ID, RunStatus.RUNNING, steps, List.of(7)))
                .isInstanceOf(ConflictException.class);
    }

    private static List<RunStep> completedMcSteps() {
        UUID runId = UUID.randomUUID();
        return List.of(
                completed(runId, 1, "generate_script", null),
                completed(runId, 2, HumanSteps.APPROVAL_CHECKPOINT, null),
                completed(runId, 3, "generate_speech", null),
                completed(runId, 4, "generate_storyboard", null),
                completed(runId, 5, "generate_keyframe", null),
                completed(runId, 6, HumanSteps.APPROVAL_CHECKPOINT, null),
                completed(runId, 7, "generate_video",
                        "{\"outputInputs\":[{\"stepOrder\":5,\"outputName\":\"keyframe.jpg\",\"inputKey\":\"keyframe\"}]}"),
                completed(runId, 8, "generate_video", null),
                completed(runId, 9, "lipsync_video",
                        "{\"outputInputs\":[{\"stepOrder\":7,\"outputName\":\"clip.mp4\",\"inputKey\":\"video\"},"
                                + "{\"stepOrder\":3,\"outputName\":\"speech.wav\",\"inputKey\":\"audio\"}]}"),
                completed(runId, 10, "render_video",
                        "{\"outputInputs\":[{\"stepOrder\":3,\"outputName\":\"speech.wav\",\"inputKey\":\"speech\"},"
                                + "{\"stepOrder\":9,\"outputName\":\"clip.mp4\",\"inputKey\":\"generated_video_1\"},"
                                + "{\"stepOrder\":8,\"outputName\":\"clip.mp4\",\"inputKey\":\"generated_video_2\"}]}"));
    }

    private static RunStep completed(UUID runId, int order, String type, String config) {
        RunStep step = new RunStep(UUID.randomUUID(), runId, (short) order, type, null, config, null);
        step.markRunning(NOW);
        step.markCompleted(null, "{}", NOW);
        return step;
    }
}
