package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.RunStepFailure;
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
import java.time.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        RunView view = WorkflowMapper.toRunView(run, List.of(step), type -> false, DOWNLOADABLE);

        assertThat(view.status()).isEqualTo("running");
        assertThat(view.steps().get(0).status()).isEqualTo("completed");
        assertThat(view.steps().get(0).output()).isEqualTo("out");
    }

    @Test
    void toRunViewReflectsErrorStatusLowercase() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        run.markError("boom", Instant.now());

        RunView view = WorkflowMapper.toRunView(run, List.of(), type -> false, DOWNLOADABLE);

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

        RunStepView view = WorkflowMapper.toRunView(run, List.of(step), type -> false, DOWNLOADABLE).steps().get(0);

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

        RunStepView view = WorkflowMapper.toRunView(run, List.of(step), type -> false, DOWNLOADABLE).steps().get(0);

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

        RunView view = WorkflowMapper.toRunView(run, List.of(probe, echo), "probe"::equals, DOWNLOADABLE);

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

        assertThat(WorkflowMapper.toRunView(run, List.of(echo), "probe"::equals, DOWNLOADABLE).input())
                .isEqualTo("hello");
    }

    @Test
    void aRunStepNamesWhereItRunsFromItsOwnType() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "clip.mp4",
                Instant.now());
        RunStep probe = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "probe");
        RunStep echo = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "echo");

        RunView view = WorkflowMapper.toRunView(run, List.of(probe, echo), "probe"::equals, DOWNLOADABLE);

        assertThat(view.steps()).extracting(RunStepView::mode).containsExactly("remote", "inline");
    }

    @Test
    void aStepWithoutOutputsHasAnEmptyOutputList() {
        RunStep step = new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo");
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());

        assertThat(WorkflowMapper.toRunView(run, List.of(step), type -> false, DOWNLOADABLE).steps().get(0).outputs())
                .isEmpty();
    }

    @Test
    void anErrorStepCarriesTheRecordOfItsCode() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "probe");
        step.markRunning(Instant.now());
        step.markError("in", "queue_timeout", "The job waited too long", Instant.now());

        RunStepView view = WorkflowMapper.toRunView(run, List.of(step), type -> false, DOWNLOADABLE).steps().get(0);

        assertThat(view.failure()).isEqualTo(new RunStepFailure("timeout", "system", true, "retry"));
    }

    @Test
    void anErrorStepWithoutACodeCarriesTheFallbackRecord() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        step.markRunning(Instant.now());
        step.markError("in", "boom", Instant.now());

        RunStepView view = WorkflowMapper.toRunView(run, List.of(step), type -> false, DOWNLOADABLE).steps().get(0);

        assertThat(view.failure()).isEqualTo(new RunStepFailure("internal", "system", true, "retry"));
    }

    @Test
    void aNonErrorStepCarriesNoRecord() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep pending = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        RunStep running = new RunStep(UUID.randomUUID(), run.getId(), (short) 2, "echo");
        running.markRunning(Instant.now());
        RunStep completed = new RunStep(UUID.randomUUID(), run.getId(), (short) 3, "echo");
        completed.markRunning(Instant.now());
        completed.markCompleted("in", "out", Instant.now());

        List<RunStepView> steps = WorkflowMapper
                .toRunView(run, List.of(pending, running, completed), type -> false, DOWNLOADABLE).steps();

        assertThat(steps).extracting(RunStepView::failure).containsOnlyNulls();
    }

    @Test
    void pendingRunStepStatusIsLowercase() {
        RunStep step = new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo");
        assertThat(step.getStatus()).isEqualTo(RunStepStatus.PENDING);

        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunView view = WorkflowMapper.toRunView(run, List.of(step), type -> false, DOWNLOADABLE);

        assertThat(view.steps().get(0).status()).isEqualTo("pending");
    }

    @Test
    void theResponseSchemaListsExactlyTheWireValuesOfTheFailureEnums() throws Exception {
        Path source = Path.of(System.getProperty("flowcortex.backend.modules.dir"),
                "workflow/src/main/java/com/flowcortex/workflow/web/RunStepResponse.java");
        String text = Files.readString(source);
        String failureRecord = text.substring(text.indexOf("record RunStepFailureResponse("));
        List<List<String>> listed = new ArrayList<>();
        Matcher allowable = Pattern.compile("allowableValues = \\{([^}]*)}").matcher(failureRecord);
        while (allowable.find()) {
            listed.add(Pattern.compile("\"([a-z_]+)\"").matcher(allowable.group(1)).results()
                    .map(match -> match.group(1)).toList());
        }

        assertThat(listed).hasSize(3);
        assertThat(listed.get(0)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(StepFailureTaxonomy.Category.values()).map(StepFailureTaxonomy.Category::wire).toList());
        assertThat(listed.get(1)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(StepFailureTaxonomy.Source.values()).map(StepFailureTaxonomy.Source::wire).toList());
        assertThat(listed.get(2)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(StepFailureTaxonomy.Recovery.values()).map(StepFailureTaxonomy.Recovery::wire).toList());
    }
}
