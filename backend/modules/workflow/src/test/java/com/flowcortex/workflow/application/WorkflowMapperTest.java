package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.RunStepOutputView;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Entity -&gt; {@code api} view mapping, including the lowercase wire-status rule. */
class WorkflowMapperTest {

    @Test
    void toWorkflowViewMapsStepsInOrder() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Demo", true);
        WorkflowStep step = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "First", "echo", null);

        WorkflowView view = WorkflowMapper.toWorkflowView(workflow, List.of(step), type -> false);

        assertThat(view.id()).isEqualTo(workflow.getId());
        assertThat(view.shared()).isTrue();
        assertThat(view.steps()).hasSize(1);
        assertThat(view.steps().get(0).type()).isEqualTo("echo");
    }

    @Test
    void eachWorkflowStepViewNamesWhereTheStepRuns() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Media first", true);
        WorkflowStep probe = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "Check", "probe", null);
        WorkflowStep echo = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 2, "Echo", "echo", null);

        WorkflowView view = WorkflowMapper.toWorkflowView(workflow, List.of(probe, echo), "probe"::equals);

        assertThat(view.steps()).extracting(WorkflowStepView::mode).containsExactly("remote", "inline");
    }

    @Test
    void toRunViewLowercasesTheStatusForTheWire() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        step.markRunning(Instant.now());
        step.markCompleted("in", "out", Instant.now());

        RunView view = WorkflowMapper.toRunView(run, List.of(step), type -> false);

        assertThat(view.status()).isEqualTo("running");
        assertThat(view.steps().get(0).status()).isEqualTo("completed");
        assertThat(view.steps().get(0).output()).isEqualTo("out");
    }

    @Test
    void toRunViewReflectsErrorStatusLowercase() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        run.markError("boom", Instant.now());

        RunView view = WorkflowMapper.toRunView(run, List.of(), type -> false);

        assertThat(view.status()).isEqualTo("error");
        assertThat(view.errorMessage()).isEqualTo("boom");
    }

    @Test
    void aStepViewCarriesLeaseProgressAndOutputsButNoStorageLocation() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        Instant pickedUpAt = Instant.parse("2026-09-30T10:00:00Z");
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "probe");
        step.markRunning(pickedUpAt, pickedUpAt.plusSeconds(1800));
        step.recordPickup(pickedUpAt, pickedUpAt.plusSeconds(120), pickedUpAt.plusSeconds(300), (short) 2);
        step.extendLease(pickedUpAt, pickedUpAt.plusSeconds(150), (short) 40, (short) 2, pickedUpAt);
        step.markCompleted("in", "{}", pickedUpAt.plusSeconds(60), "[{\"name\":\"video-details.json\","
                + "\"bucket\":\"flowcortex-media\",\"objectKey\":\"runs/x/steps/1/video-details.json\","
                + "\"mime\":\"application/json\",\"bytes\":42,\"meta\":{\"k\":\"v\"}}]");

        RunStepView view = WorkflowMapper.toRunView(run, List.of(step), type -> false).steps().get(0);

        assertThat(view.pickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(view.progressPercent()).isEqualTo(40);
        assertThat(view.deliveryCount()).isEqualTo(2);
        assertThat(view.attempt()).isEqualTo(1);
        assertThat(view.errorCode()).isNull();
        assertThat(view.outputs()).containsExactly(new RunStepOutputView("video-details.json", "application/json", 42L));
    }

    @Test
    void aRemoteStepsInputNeverShowsWhereTheObjectIsStoredButTheRunShowsTheFileName() {
        String reference = "{\"key\":\"source\",\"bucket\":\"flowcortex-uploads\",\"objectKey\":\"owner/upload\"}";
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "clip.mp4",
                Instant.now());
        RunStep probe = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "probe", reference);
        probe.markRunning(Instant.now());
        probe.markCompleted(reference, "{\"videoCodec\":\"h264\"}", Instant.now());
        RunStep echo = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "echo");
        echo.markRunning(Instant.now());
        echo.markCompleted("{\"videoCodec\":\"h264\"}", "{\"videoCodec\":\"h264\"}", Instant.now());

        RunView view = WorkflowMapper.toRunView(run, List.of(probe, echo), "probe"::equals);

        assertThat(view.input()).isEqualTo("clip.mp4");
        assertThat(view.steps().get(0).input()).isNull();
        assertThat(view.steps().get(0).output()).isEqualTo("{\"videoCodec\":\"h264\"}");
        assertThat(view.steps().get(1).input()).isEqualTo("{\"videoCodec\":\"h264\"}");
    }

    @Test
    void aRunWhoseFirstStepIsInlineKeepsItsInput() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hello",
                Instant.now());
        RunStep echo = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");

        assertThat(WorkflowMapper.toRunView(run, List.of(echo), "probe"::equals).input()).isEqualTo("hello");
    }

    @Test
    void aRunStepNamesWhereItRunsFromItsOwnType() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "clip.mp4",
                Instant.now());
        RunStep probe = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "probe");
        RunStep echo = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "echo");

        RunView view = WorkflowMapper.toRunView(run, List.of(probe, echo), "probe"::equals);

        assertThat(view.steps()).extracting(RunStepView::mode).containsExactly("remote", "inline");
    }

    @Test
    void aStepWithoutOutputsHasAnEmptyOutputList() {
        RunStep step = new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo");
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());

        assertThat(WorkflowMapper.toRunView(run, List.of(step), type -> false).steps().get(0).outputs()).isEmpty();
    }

    @Test
    void pendingRunStepStatusIsLowercase() {
        RunStep step = new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo");
        assertThat(step.getStatus()).isEqualTo(RunStepStatus.PENDING);

        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunView view = WorkflowMapper.toRunView(run, List.of(step), type -> false);

        assertThat(view.steps().get(0).status()).isEqualTo("pending");
    }
}
