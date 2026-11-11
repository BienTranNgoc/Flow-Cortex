package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link WorkflowServiceImpl}. */
class WorkflowServiceImplTest {

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowStepRepository workflowStepRepository = mock(WorkflowStepRepository.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final StepExecutorRegistry registry =
            new StepExecutorRegistry(mediaJobs, Clock.systemUTC());
    private final WorkflowServiceImpl service = new WorkflowServiceImpl(workflowRepository, workflowStepRepository,
            registry, Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void listVisibleWorkflowsMapsEachWithItsSteps() {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "Demo", true);
        WorkflowStep step = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "First", "echo", null);
        when(workflowRepository.findAllVisibleTo(ownerId)).thenReturn(List.of(workflow));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(List.of(step));

        List<WorkflowView> views = service.listVisibleWorkflows(ownerId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo(workflow.getId());
        assertThat(views.get(0).steps()).hasSize(1);
    }

    @Test
    void getVisibleWorkflowNotVisibleIsNotFound() {
        UUID ownerId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findVisibleTo(workflowId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVisibleWorkflow(ownerId, workflowId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getVisibleWorkflowReturnsItWithSteps() {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "Demo", false);
        when(workflowRepository.findVisibleTo(workflow.getId(), ownerId)).thenReturn(Optional.of(workflow));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(List.of());

        WorkflowView view = service.getVisibleWorkflow(ownerId, workflow.getId());

        assertThat(view.name()).isEqualTo("Demo");
        assertThat(view.steps()).isEmpty();
    }

    @Test
    void createWorkflowAssignsStepOrderByListPosition() {
        UUID ownerId = UUID.randomUUID();
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowView created = service.createWorkflow(ownerId, "Two Steps", true, List.of(
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "echo", "{step:1.field}")));

        assertThat(created.ownerId()).isEqualTo(ownerId);
        assertThat(created.shared()).isTrue();
        assertThat(created.steps()).extracting(step -> step.stepOrder()).containsExactly(1, 2);
    }

    @Test
    void createWorkflowRejectsAnUnknownStepTypeNamingItsPositionAndSavesNothing() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "transcode", null),
                new NewWorkflowStep("third", "fail", null),
                new NewWorkflowStep("fourth", "demucs-typo", null));

        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Bad", false, steps))
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> assertThat(((ConstraintViolationException) e).getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .containsExactlyInAnyOrder(
                                "steps[1].type unknown capability 'transcode'; allowed: APPROVAL_CHECKPOINT, align_audio, "
                                        + "echo, extract_audio, fail, fetch_video, generate_script, "
                                        + "generate_speech, generate_storyboard, generate_video, ingest_image, "
                                        + "ingest_video, probe, render_video",
                                "steps[3].type unknown capability 'demucs-typo'; allowed: APPROVAL_CHECKPOINT, "
                                        + "align_audio, echo, extract_audio, fail, fetch_video, "
                                        + "generate_script, generate_speech, generate_storyboard, generate_video, "
                                        + "ingest_image, ingest_video, probe, render_video"));

        verify(workflowRepository, never()).save(any());
        verify(workflowStepRepository, never()).saveAll(any());
    }

    @Test
    void createWorkflowAcceptsConsecutiveMediaStepsWhenTheFirstStepIsRemote() {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowView created = service.createWorkflow(UUID.randomUUID(), "Analyze media", false, List.of(
                new NewWorkflowStep("Inspect media", "probe", null),
                new NewWorkflowStep("Extract audio", "extract_audio", null)));

        assertThat(created.steps()).extracting(step -> step.type())
                .containsExactly("probe", "extract_audio");
        assertThat(created.steps()).extracting(step -> step.mode())
                .containsExactly("remote", "remote");
    }

    @Test
    void createWorkflowRejectsARemoteStepWhenTheWorkflowStartsInline() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("extract", "extract_audio", null));

        assertThatThrownBy(() -> service.createWorkflow(
                UUID.randomUUID(), "Bad order", false, steps))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("workflow must start with a media step");

        verify(workflowRepository, never()).save(any());
        verify(workflowStepRepository, never()).saveAll(any());
    }

    @Test
    void createWorkflowAcceptsAProviderStepAfterAnInlineStepBecauseItNeedsNoUploadedFile() {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowView created = service.createWorkflow(UUID.randomUUID(), "Script from text", false, List.of(
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("script", "generate_script", null),
                new NewWorkflowStep("storyboard", "generate_storyboard", null),
                new NewWorkflowStep("clip", "generate_video", null)));

        assertThat(created.steps()).extracting(step -> step.type())
                .containsExactly("echo", "generate_script", "generate_storyboard", "generate_video");
    }

    @Test
    void createWorkflowAcceptsAMediaFirstStepFollowedByInlineSteps() {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowView created = service.createWorkflow(UUID.randomUUID(), "Media first", false, List.of(
                new NewWorkflowStep("first", "probe", null),
                new NewWorkflowStep("second", "echo", null)));

        assertThat(created.steps()).extracting(step -> step.type()).containsExactly("probe", "echo");
        assertThat(created.steps()).extracting(step -> step.mode()).containsExactly("remote", "inline");
    }

    @Test
    void createWorkflowRefusesARemoteStepBehindAnInlineFirstStepAtThatStepsType() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("first", "echo", null),
                new NewWorkflowStep("second", "probe", null));

        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Bad order", false, steps))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("steps[1].type"));

        verify(workflowRepository, never()).save(any());
    }

    @Test
    void createWorkflowKeepsTheOutputInputsAStepDeclaresOnEarlierSteps() {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String config = "{\"label\":\"x\",\"outputInputs\":["
                + "{\"stepOrder\":2,\"outputName\":\"source-audio.wav\",\"inputKey\":\"audio\"},"
                + "{\"stepOrder\":1,\"outputName\":\"voice.wav\",\"inputKey\":\"voice\",\"optional\":true}]}";

        service.createWorkflow(UUID.randomUUID(), "Declared inputs", false, List.of(
                new NewWorkflowStep("probe", "probe", null),
                new NewWorkflowStep("extract", "extract_audio", null),
                new NewWorkflowStep("compose", "render_video", null, config)));

        org.mockito.ArgumentCaptor<List<WorkflowStep>> saved = org.mockito.ArgumentCaptor.captor();
        verify(workflowStepRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(WorkflowStep::getConfig).containsExactly(null, null, config);
    }

    @Test
    void createWorkflowRefusesAnOutputInputsEntryThatIsNotAnEarlierStepOrNotWellFormedAtThatStepsConfig() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("probe", "probe", null),
                remote("self", "[" + entry("2", "\"a.wav\"", "\"a\"") + "]"),
                remote("forward", "[" + entry("5", "\"x.wav\"", "\"x\"") + "]"),
                remote("order zero", "[" + entry("0", "\"x.wav\"", "\"x\"") + "]"),
                remote("order text", "[" + entry("\"1\"", "\"x.wav\"", "\"x\"") + "]"),
                remote("no name", "[{\"stepOrder\":1,\"inputKey\":\"x\"}]"),
                remote("blank key", "[" + entry("1", "\"x.wav\"", "\" \"") + "]"),
                remote("not an entry", "[\"step:1/x.wav\"]"),
                remote("not a list", "{\"stepOrder\":1}"),
                remote("empty", "[]"),
                remote("optional text", "[{\"stepOrder\":1,\"outputName\":\"x.wav\",\"inputKey\":\"x\","
                        + "\"optional\":\"yes\"}]"),
                new NewWorkflowStep("not json", "extract_audio", null, "{outputInputs"));

        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Bad inputs", false, steps))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("steps[1].config", "steps[2].config", "steps[3].config",
                                "steps[4].config", "steps[5].config", "steps[6].config", "steps[7].config",
                                "steps[8].config", "steps[9].config", "steps[10].config", "steps[11].config"));

        verify(workflowRepository, never()).save(any());
        verify(workflowStepRepository, never()).saveAll(any());
    }

    @Test
    void createWorkflowRefusesAStepWhoseOutputInputsPointForwardNamingThatStep() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("probe", "probe", null),
                new NewWorkflowStep("extract", "extract_audio", null),
                remote("transcribe", "[" + entry("5", "\"source-audio.wav\"", "\"audio\"") + "]"));

        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Forward", false, steps))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .containsExactly("steps[2].config outputInputs reads step 5, which does not run before "
                                + "step 3"));

        verify(workflowRepository, never()).save(any());
        verify(workflowStepRepository, never()).saveAll(any());
    }

    @Test
    void createWorkflowRefusesTwoOutputInputsEntriesWithOneInputKeyAtThatStepsConfig() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("probe", "probe", null),
                new NewWorkflowStep("extract", "extract_audio", null),
                remote("compose", "[" + entry("1", "\"a.wav\"", "\"music\"") + ","
                        + entry("2", "\"b.wav\"", "\"music\"") + "]"));

        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Duplicate", false, steps))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .containsExactly("steps[2].config outputInputs gives input key 'music' twice"));

        verify(workflowRepository, never()).save(any());
    }

    @Test
    void createWorkflowRefusesOutputInputsOnAStepThatDoesNotRunRemotelyAtThatStepsConfig() {
        String config = "{\"outputInputs\":[" + entry("1", "\"a.wav\"", "\"a\"") + "]}";
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("probe", "probe", null),
                new NewWorkflowStep("echo", "echo", null, config),
                new NewWorkflowStep("review", "APPROVAL_CHECKPOINT", null, config),
                new NewWorkflowStep("no inputs", "echo", null, "{\"label\":\"x\"}"));

        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Inline inputs", false, steps))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .containsExactlyInAnyOrder(
                                "steps[1].config only a remote step reads outputInputs; 'echo' runs inline",
                                "steps[2].config only a remote step reads outputInputs; 'APPROVAL_CHECKPOINT' "
                                        + "runs inline"));

        verify(workflowRepository, never()).save(any());
    }

    private static final String PROBE_FROM_STEP_1 =
            "{\"outputInputs\":[{\"stepOrder\":1,\"outputName\":\"video\",\"inputKey\":\"media\"}]}";
    private static final String NEEDS_UPLOAD =
            "'probe' needs an uploaded file, so the workflow must start with a media step";
    private static final String AFTER_INGEST =
            "'probe' follows an ingest step, so it must read the file through outputInputs";

    @Test
    void createWorkflowAcceptsAMediaStepThatReadsTheFileOfALinkStepThroughOutputInputs() {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowView created = service.createWorkflow(UUID.randomUUID(), "Link then probe", false, List.of(
                new NewWorkflowStep("fetch", "fetch_video", null),
                new NewWorkflowStep("probe", "probe", null, PROBE_FROM_STEP_1)));

        assertThat(created.steps()).extracting(step -> step.type()).containsExactly("fetch_video", "probe");
    }

    @Test
    void createWorkflowAcceptsAMediaStepThatReadsTheFileOfAnIngestStepThroughOutputInputs() {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowView created = service.createWorkflow(UUID.randomUUID(), "Upload then probe", false, List.of(
                new NewWorkflowStep("ingest", "ingest_video", null),
                new NewWorkflowStep("probe", "probe", null, PROBE_FROM_STEP_1)));

        assertThat(created.steps()).extracting(step -> step.type()).containsExactly("ingest_video", "probe");
    }

    @Test
    void createWorkflowRefusesAMediaStepAfterATextStepEvenWhenItNamesThatStepInOutputInputs() {
        assertRefused("steps[1].type", NEEDS_UPLOAD,
                new NewWorkflowStep("say", "echo", null),
                new NewWorkflowStep("probe", "probe", null, PROBE_FROM_STEP_1));
    }

    @Test
    void createWorkflowRefusesAMediaStepAfterALinkStepWithoutOutputInputs() {
        assertRefused("steps[1].type", NEEDS_UPLOAD,
                new NewWorkflowStep("fetch", "fetch_video", null),
                new NewWorkflowStep("probe", "probe", null));
    }

    @Test
    void createWorkflowRefusesAMediaStepAfterAnIngestStepWithoutOutputInputs() {
        assertRefused("steps[1].type", AFTER_INGEST,
                new NewWorkflowStep("ingest", "ingest_video", null),
                new NewWorkflowStep("probe", "probe", null));
        assertRefused("steps[1].type", AFTER_INGEST,
                new NewWorkflowStep("ingest", "ingest_video", null),
                new NewWorkflowStep("probe", "probe", null, "{\"label\":\"x\"}"));
    }

    @Test
    void createWorkflowRefusesOutputInputsThatNameAnInlineTextStepAfterALinkStep() {
        String fromEcho = "{\"outputInputs\":[{\"stepOrder\":2,\"outputName\":\"video\",\"inputKey\":\"media\"}]}";

        assertRefused("steps[2].type", NEEDS_UPLOAD,
                new NewWorkflowStep("fetch", "fetch_video", null),
                new NewWorkflowStep("say", "echo", null),
                new NewWorkflowStep("probe", "probe", null, fromEcho));
    }

    @Test
    void createWorkflowRefusesAFetchVideoConfigWithAnUnknownKeyOrBadQualityUnderThatStepsConfig() {
        for (String config : List.of("{\"quality\":\"4k\"}", "{\"link\":\"https://x.example/a.mp4\"}",
                "{\"url\":\"https://user:pw@cdn.example.com/a.mp4\"}")) {
            assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Bad config", false,
                    List.of(new NewWorkflowStep("fetch", "fetch_video", null, config))))
                    .isInstanceOfSatisfying(ConstraintViolationException.class, e -> {
                        assertThat(e.getConstraintViolations())
                                .extracting(violation -> violation.getPropertyPath().toString())
                                .containsExactly("steps[0].config");
                        assertThat(e.getConstraintViolations())
                                .extracting(violation -> violation.getMessage())
                                .noneMatch(message -> message.contains("pw"));
                    });
        }

        verify(workflowRepository, never()).save(any());
    }

    @Test
    void createWorkflowSavesAFetchVideoWithAValidConfig() {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String config = "{\"url\":\"https://www.tiktok.com/@a/video/1\",\"quality\":\"720p\",\"extractAudio\":false}";

        WorkflowView created = service.createWorkflow(UUID.randomUUID(), "Fetch", false,
                List.of(new NewWorkflowStep("fetch", "fetch_video", null, config)));

        assertThat(created.steps()).extracting(step -> step.type()).containsExactly("fetch_video");
    }

    private void assertRefused(String path, String message, NewWorkflowStep... steps) {
        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Refused", false, List.of(steps)))
                .isInstanceOfSatisfying(ConstraintViolationException.class,
                        e -> assertThat(e.getConstraintViolations())
                                .extracting(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                                .containsExactly(path + " " + message));

        verify(workflowRepository, never()).save(any());
        verify(workflowStepRepository, never()).saveAll(any());
    }

    private static NewWorkflowStep remote(String name, String outputInputs) {
        return new NewWorkflowStep(name, "extract_audio", null, "{\"outputInputs\":" + outputInputs + "}");
    }

    private static String entry(String stepOrder, String outputName, String inputKey) {
        return "{\"stepOrder\":" + stepOrder + ",\"outputName\":" + outputName + ",\"inputKey\":" + inputKey + "}";
    }
}
