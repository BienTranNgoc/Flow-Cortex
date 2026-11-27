package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.RunStepChildView;
import com.flowcortex.workflow.api.RunStepOutputView;
import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.api.WorkflowVisibility;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Entity -&gt; {@code api} view mapping, including the lowercase wire-status rule. */
class WorkflowMapperTest {

    private static final Predicate<String> DOWNLOADABLE = "flowcortex-media"::equals;

    @Test
    void toWorkflowViewMapsStepsInOrder() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Demo", true);
        WorkflowStep step = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "First", "echo", null);

        WorkflowView view = WorkflowMapper.toWorkflowView(workflow, List.of(step), type -> false, type -> true,
                type -> false);

        assertThat(view.id()).isEqualTo(workflow.getId());
        assertThat(view.visibility()).isEqualTo(WorkflowVisibility.PUBLIC);
        assertThat(view.steps()).hasSize(1);
        assertThat(view.steps().get(0).type()).isEqualTo("echo");
    }

    @Test
    void aWorkflowIsAvailableOnlyWhenEveryStepTypeIs() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Dub", true);
        WorkflowStep echo = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "Echo", "echo", null);
        WorkflowStep translate =
                new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 2, "Translate", "translate", null);

        assertThat(WorkflowMapper.toWorkflowView(workflow, List.of(echo, translate), type -> false,
                type -> !"translate".equals(type), type -> false).available()).isFalse();
        assertThat(WorkflowMapper.toWorkflowView(workflow, List.of(echo, translate), type -> false, type -> true,
                type -> false).available()).isTrue();
    }

    @Test
    void eachWorkflowStepViewNamesWhereTheStepRuns() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Media first", true);
        WorkflowStep probe = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "Check", "probe", null);
        WorkflowStep echo = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 2, "Echo", "echo", null);

        WorkflowView view = WorkflowMapper.toWorkflowView(workflow, List.of(probe, echo), "probe"::equals,
                type -> true, type -> false);

        assertThat(view.steps()).extracting(WorkflowStepView::mode).containsExactly("remote", "inline");
    }

    @Test
    void startInputFollowsTheFirstStep() {
        assertThat(startInputOf("fetch_video", null)).isEqualTo("link");
        assertThat(startInputOf("fetch_video", "{\"quality\":\"720p\"}")).isEqualTo("link");
        assertThat(startInputOf("fetch_video", "{\"url\":\"https://cdn.example.com/a.mp4\"}")).isEqualTo("text");
        assertThat(startInputOf("ingest_image", null)).isEqualTo("image");
        assertThat(startInputOf("ingest_video", null)).isEqualTo("upload");
        assertThat(startInputOf("probe", null)).isEqualTo("upload");
        assertThat(startInputOf("generate_script", null)).isEqualTo("upload");
        assertThat(startInputOf("generate_speech", null)).isEqualTo("text");
        assertThat(startInputOf("echo", null)).isEqualTo("text");
    }

    @Test
    void aWorkflowWithoutStepsStartsFromText() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Empty", true);

        assertThat(WorkflowMapper.toWorkflowView(workflow, List.of(), type -> false, type -> true, type -> true)
                .startInput()).isEqualTo("text");
    }

    private static String startInputOf(String firstType, String firstConfig) {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Demo", true);
        WorkflowStep first = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "First", firstType,
                null, firstConfig);
        StepExecutorRegistry registry = new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC());
        return WorkflowMapper.toWorkflowView(workflow, List.of(first), registry::isRemote, registry::isAvailable,
                registry::snapshotsRunInputs).startInput();
    }

    @Test
    void toRunViewLowercasesTheStatusForTheWire() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        step.markRunning(Instant.now());
        step.markCompleted("in", "out", Instant.now());

        RunView view = runView(run, List.of(step), List.of(), type -> false, DOWNLOADABLE);

        assertThat(view.status()).isEqualTo("running");
        assertThat(view.steps().get(0).status()).isEqualTo("completed");
        assertThat(view.steps().get(0).output()).isEqualTo("out");
    }

    @Test
    void toRunViewReflectsErrorStatusLowercase() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        run.markError("boom", Instant.now());

        RunView view = runView(run, List.of(), List.of(), type -> false, DOWNLOADABLE);

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

        RunStepView view = runView(run, List.of(step), List.of(), type -> false, DOWNLOADABLE).steps().get(0);

        assertThat(view.pickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(view.progressPercent()).isEqualTo(40);
        assertThat(view.deliveryCount()).isEqualTo(2);
        assertThat(view.attempt()).isEqualTo(1);
        assertThat(view.errorCode()).isNull();
        assertThat(view.outputs()).containsExactly(new RunStepOutputView("video-details.json", "application/json", 42L));
    }

    @Test
    void anOutputThatCannotBeDownloadedIsNotOfferedOnTheRunView() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 7, "tts");
        step.markRunning(Instant.now());
        step.markCompleted("in", "{}", Instant.now(), "[{\"name\":\"voice.wav\",\"bucket\":\"flowcortex-uploads\","
                + "\"objectKey\":\"runs/x/steps/7/voice.wav\",\"mime\":\"audio/wav\",\"bytes\":4},"
                + "{\"name\":\"voice-manifest.json\",\"bucket\":\"flowcortex-media\","
                + "\"objectKey\":\"runs/x/steps/7/voice-manifest.json\",\"mime\":\"application/json\",\"bytes\":2}]");

        RunStepView view = runView(run, List.of(step), List.of(), type -> false, DOWNLOADABLE).steps().get(0);

        assertThat(view.outputs())
                .containsExactly(new RunStepOutputView("voice-manifest.json", "application/json", 2L));
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

        RunView view = runView(run, List.of(probe, echo), List.of(), "probe"::equals, DOWNLOADABLE);

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

        assertThat(runView(run, List.of(echo), List.of(), "probe"::equals, DOWNLOADABLE).input())
                .isEqualTo("hello");
    }

    @Test
    void aRunStepNamesWhereItRunsFromItsOwnType() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "clip.mp4",
                Instant.now());
        RunStep probe = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "probe");
        RunStep echo = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "echo");

        RunView view = runView(run, List.of(probe, echo), List.of(), "probe"::equals, DOWNLOADABLE);

        assertThat(view.steps()).extracting(RunStepView::mode).containsExactly("remote", "inline");
    }

    @Test
    void aStepWithoutOutputsHasAnEmptyOutputList() {
        RunStep step = new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo");
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());

        assertThat(runView(run, List.of(step), List.of(), type -> false, DOWNLOADABLE).steps().get(0).outputs())
                .isEmpty();
    }

    @Test
    void pendingRunStepStatusIsLowercase() {
        RunStep step = new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo");
        assertThat(step.getStatus()).isEqualTo(RunStepStatus.PENDING);

        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunView view = runView(run, List.of(step), List.of(), type -> false, DOWNLOADABLE);

        assertThat(view.steps().get(0).status()).isEqualTo("pending");
    }

    @Test
    void iterateStepCarriesItsCurrentChildrenInItemOrder() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep first = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        RunStep iterate = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "iterate");
        Instant at = Instant.parse("2026-11-20T10:00:00Z");
        RunStep item0 = RunStep.child(run.getId(), (short) 2, "echo", iterate.getId(), (short) 1, (short) 0, "a");
        item0.markRunning(at);
        item0.markCompleted("a", "A", at.plusSeconds(1), "[{\"name\":\"item-0-1-voice.wav\","
                + "\"bucket\":\"flowcortex-media\",\"objectKey\":\"runs/x/steps/2/item-0-1-voice.wav\","
                + "\"mime\":\"audio/wav\",\"bytes\":4}]");
        RunStep item1 = RunStep.child(run.getId(), (short) 2, "echo", iterate.getId(), (short) 1, (short) 1, "b");
        item1.markRunning(at);
        item1.markError("b", "echo_failed", "no luck", at.plusSeconds(2));
        RunStep item2 = RunStep.child(run.getId(), (short) 2, "echo", iterate.getId(), (short) 1, (short) 2, "c");

        RunView view = runView(run, List.of(first, iterate), List.of(item0, item1, item2),
                type -> false, DOWNLOADABLE);

        assertThat(view.steps().get(0).children()).isEmpty();
        List<RunStepChildView> children = view.steps().get(1).children();
        assertThat(children).extracting(RunStepChildView::itemIndex).containsExactly(0, 1, 2);
        assertThat(children).extracting(RunStepChildView::status).containsExactly("completed", "error", "pending");
        assertThat(children.get(0).id()).isEqualTo(item0.getId());
        assertThat(children.get(0).output()).isEqualTo("A");
        assertThat(children.get(0).attempt()).isEqualTo(1);
        assertThat(children.get(0).startedAt()).isEqualTo(at);
        assertThat(children.get(0).finishedAt()).isEqualTo(at.plusSeconds(1));
        assertThat(children.get(0).outputs())
                .containsExactly(new RunStepOutputView("item-0-1-voice.wav", "audio/wav", 4L));
        assertThat(children.get(1).errorCode()).isEqualTo("echo_failed");
        assertThat(children.get(1).errorMessage()).isEqualTo("no luck");
        assertThat(children.get(2).outputs()).isEmpty();
    }

    @Test
    void anIterateStepWithNoChildrenYetHasAnEmptyList() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep iterate = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "iterate");

        assertThat(runView(run, List.of(iterate), List.of(), type -> false, DOWNLOADABLE).steps()
                .get(0).children()).isEmpty();
    }

    @Test
    void otherStepsCarryNoChildren() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep echo = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        RunStep iterate = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "iterate");
        RunStep item = RunStep.child(run.getId(), (short) 2, "echo", iterate.getId(), (short) 1, (short) 0, "a");

        RunView view = runView(run, List.of(echo, iterate), List.of(item), type -> false,
                DOWNLOADABLE);

        assertThat(view.steps().get(0).children()).isEmpty();
        assertThat(view.steps().get(1).children()).hasSize(1);
    }

    @Test
    void anUneditedCompletedEchoStepIsEditableAtRevisionOneWithItsOriginalTextAsTheField() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        step.markRunning(Instant.now());
        step.markCompleted("in", "Hello world", Instant.now());

        RunStepView view = runView(run, List.of(step), List.of(), type -> false, DOWNLOADABLE).steps().get(0);

        assertThat(view.editable()).isTrue();
        assertThat(view.editRevision()).isEqualTo(1);
        assertThat(view.editedOutput()).isNull();
        assertThat(view.editedAt()).isNull();
        assertThat(view.editDiffPct()).isNull();
        assertThat(view.editableFields()).singleElement().satisfies(field -> {
            assertThat(field.pointer()).isEmpty();
            assertThat(field.label()).isEqualTo("output");
            assertThat(field.text()).isEqualTo("Hello world");
        });
    }

    @Test
    void aCarriedEditShowsBesideTheOriginalAndTheFieldsCarryTheEditedText() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        Instant editedAt = Instant.now();
        RunStep step = RunStep.reused(UUID.randomUUID(), run.getId(), (short) 1, "echo", "in", null, null,
                "Hello world", null, Instant.now(), Instant.now(), "Hello Hanoi", editedAt,
                new BigDecimal("45.45"), 1);

        RunStepView view = runView(run, List.of(step), List.of(), type -> false, DOWNLOADABLE).steps().get(0);

        assertThat(view.output()).isEqualTo("Hello world");
        assertThat(view.editedOutput()).isEqualTo("Hello Hanoi");
        assertThat(view.editedAt()).isEqualTo(editedAt);
        assertThat(view.editDiffPct()).isEqualByComparingTo("45.45");
        assertThat(view.editRevision()).isEqualTo(2);
        assertThat(view.editableFields()).extracting(field -> field.text()).containsExactly("Hello Hanoi");
    }

    @Test
    void aRunningEchoStepAndAnIterateStepAreNotEditableAndOfferNoFields() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep running = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        running.markRunning(Instant.now());
        RunStep iterate = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "iterate");
        iterate.markRunning(Instant.now());
        iterate.markCompleted("in", "[\"a\"]", Instant.now());

        RunView view = runView(run, List.of(running, iterate), List.of(), type -> false, DOWNLOADABLE);

        assertThat(view.steps()).extracting(RunStepView::editable).containsExactly(false, false);
        assertThat(view.steps()).extracting(step -> step.editableFields().size()).containsExactly(0, 0);
        assertThat(view.steps()).extracting(RunStepView::editRevision).containsExactly(1, 1);
    }

    private static RunView runView(WorkflowRun run, List<RunStep> steps, List<RunStep> children,
            Predicate<String> isRemoteType, Predicate<String> isDownloadable) {
        return WorkflowMapper.toRunView(run, steps, children, isRemoteType, isDownloadable,
                RunViewFacts.of(steps, false));
    }
}
