package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.StepEdit;
import com.flowcortex.workflow.api.UpdateWorkflow;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
    void getVisibleWorkflowNotVisibleIsNotFound() {
        UUID ownerId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findVisibleTo(workflowId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVisibleWorkflow(ownerId, workflowId))
                .isInstanceOf(NotFoundException.class);
    }

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BEN = UUID.randomUUID();
    private static final UUID CLEO = UUID.randomUUID();
    private static final UUID NIL_OWNER = new UUID(0L, 0L);
    private static final int CONFIG_CAP = 50_000;

    private Workflow stored(Workflow workflow) {
        when(workflowRepository.findByIdAndDeletedAtIsNull(workflow.getId())).thenReturn(Optional.of(workflow));
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return workflow;
    }

    private List<WorkflowStep> withSteps(Workflow workflow, String... types) {
        List<WorkflowStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            steps.add(new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) (i + 1), "s" + (i + 1), types[i],
                    null));
        }
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(steps);
        when(workflowStepRepository.findMaxStepOrder(workflow.getId())).thenReturn(types.length);
        for (WorkflowStep step : steps) {
            when(workflowStepRepository.findById(step.getId())).thenReturn(Optional.of(step));
        }
        return steps;
    }

    private static Workflow published(UUID owner, boolean shared) {
        return new Workflow(UUID.randomUUID(), owner, "W", shared);
    }

    private static Workflow draft(UUID owner, boolean shared) {
        return Workflow.draft(UUID.randomUUID(), owner, "W", shared);
    }

    private static org.assertj.core.api.ThrowableAssert.ThrowingCallable[] memberWrites(WorkflowServiceImpl service,
            UUID callerId, Workflow workflow) {
        UUID stepId = UUID.randomUUID();
        return new org.assertj.core.api.ThrowableAssert.ThrowingCallable[] {
            () -> service.updateWorkflow(callerId, workflow.getId(), new UpdateWorkflow("x", null, null)),
            () -> service.deleteWorkflow(callerId, workflow.getId()),
            () -> service.addStep(callerId, workflow.getId(), new NewStep(null, "echo", null, null)),
            () -> service.updateStep(callerId, workflow.getId(), stepId, new StepEdit("x", null, null))
        };
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(status));
    }

    @Test
    void aNonOwnerWhoCanSeeTheWorkflowGetsForbidden() {
        Workflow workflow = stored(published(ANA, true));
        withSteps(workflow, "echo");

        for (var write : memberWrites(service, BEN, workflow)) {
            assertThatThrownBy(write).satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        }

        verify(workflowRepository, never()).save(any());
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void aNonOwnerWhoCannotSeeTheDraftGetsNotFound() {
        Workflow sharedDraft = stored(draft(ANA, true));
        Workflow privatePublished = stored(published(ANA, false));

        for (Workflow workflow : List.of(sharedDraft, privatePublished)) {
            for (var write : memberWrites(service, BEN, workflow)) {
                assertThatThrownBy(write).isInstanceOf(NotFoundException.class);
            }
        }
    }

    @Test
    void theSeededNilOwnerWorkflowIsForbiddenToEveryMember() {
        Workflow seeded = stored(published(NIL_OWNER, true));

        for (UUID member : List.of(ANA, BEN)) {
            for (var write : memberWrites(service, member, seeded)) {
                assertThatThrownBy(write).satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
            }
        }
    }

    @Test
    void publishingAPublishedWorkflowIsANoOp() {
        Workflow workflow = stored(published(ANA, true));
        withSteps(workflow);

        WorkflowView view = service.updateWorkflow(ANA, workflow.getId(), new UpdateWorkflow(null, null, "published"));

        assertThat(view.status()).isEqualTo("published");
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);
    }

    @Test
    void unpublishingMovesBackToDraft() {
        Workflow workflow = stored(published(ANA, true));
        withSteps(workflow);

        WorkflowView unpublished = service.updateWorkflow(ANA, workflow.getId(),
                new UpdateWorkflow(null, null, "draft"));
        WorkflowView republished = service.updateWorkflow(ANA, workflow.getId(),
                new UpdateWorkflow(null, null, "published"));

        assertThat(unpublished.status()).isEqualTo("draft");
        assertThat(republished.status()).isEqualTo("published");
    }

    @Test
    void aDeletedWorkflowIsNotFound() {
        UUID deletedId = UUID.randomUUID();
        when(workflowRepository.findByIdAndDeletedAtIsNull(deletedId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateWorkflow(ANA, deletedId, new UpdateWorkflow("x", null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteWorkflow(ANA, deletedId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteWorkflow(Caller.admin(CLEO), deletedId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anAdminChangesAnotherOwnersDraftAndPublishedWorkflowButNotADeletedOne() {
        Caller admin = Caller.admin(CLEO);
        for (Workflow workflow : List.of(stored(draft(ANA, false)), stored(published(ANA, true)),
                stored(published(NIL_OWNER, true)))) {
            withSteps(workflow);

            WorkflowView renamed = service.updateWorkflow(admin, workflow.getId(),
                    new UpdateWorkflow("Fixed", null, null));
            assertThat(renamed.name()).isEqualTo("Fixed");
            assertThat(renamed.ownerId()).isEqualTo(workflow.getOwnerId());

            service.deleteWorkflow(admin, workflow.getId());
            assertThat(workflow.getDeletedAt()).isNotNull();

            for (var write : memberWrites(service, BEN, workflow)) {
                assertThatThrownBy(write).isInstanceOf(RuntimeException.class);
            }
        }
        UUID deletedId = UUID.randomUUID();
        when(workflowRepository.findByIdAndDeletedAtIsNull(deletedId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateWorkflow(admin, deletedId, new UpdateWorkflow("x", null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theProductVideoWorkflowIsReadOnlyEvenForAnAdmin() {
        Caller admin = Caller.admin(CLEO);
        Workflow productVideo = stored(new Workflow(ProductVideoWorkflow.ID, NIL_OWNER, "Product Video", true));
        List<WorkflowStep> pvSteps = withSteps(productVideo, "echo");
        UUID pvStepId = pvSteps.get(0).getId();
        Workflow other = stored(published(NIL_OWNER, true));
        List<WorkflowStep> otherSteps = withSteps(other, "echo");

        assertThatThrownBy(() -> service.updateWorkflow(admin, productVideo.getId(),
                new UpdateWorkflow("x", null, "draft"))).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetail().code())
                        .isEqualTo("workflow.system.readOnly"));
        assertThatThrownBy(() -> service.deleteWorkflow(admin, productVideo.getId()))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.addStep(admin, productVideo.getId(), new NewStep(null, "echo", null, null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.updateStep(admin, productVideo.getId(), pvStepId,
                new StepEdit("x", null, null))).isInstanceOf(ConflictException.class);
        assertThat(productVideo.getDeletedAt()).isNull();
        assertThat(productVideo.getName()).isEqualTo("Product Video");
        verify(workflowStepRepository, never()).save(any());

        service.updateWorkflow(admin, other.getId(), new UpdateWorkflow("renamed", null, null));
        service.addStep(admin, other.getId(), new NewStep(null, "echo", null, null));
        service.updateStep(admin, other.getId(), otherSteps.get(0).getId(), new StepEdit("renamed", null, null));
        service.deleteWorkflow(admin, other.getId());
        assertThat(other.getDeletedAt()).isNotNull();
    }

    @Test
    void appendingTakesTheNextStepOrder() {
        Workflow workflow = stored(draft(ANA, false));
        withSteps(workflow, "echo", "echo");

        WorkflowStepView added = service.addStep(ANA, workflow.getId(), new NewStep("Say hi", "echo", null, null));

        assertThat(added.stepOrder()).isEqualTo(3);
        assertThat(added.type()).isEqualTo("echo");
        assertThat(added.name()).isEqualTo("Say hi");
    }

    @Test
    void appendingAFileReadingStepAfterAnInlineFirstStepIsRefusedNamingItsPosition() {
        Workflow workflow = stored(draft(ANA, false));
        withSteps(workflow, "echo");

        assertThatThrownBy(() -> service.addStep(ANA, workflow.getId(), new NewStep(null, "extract_audio", null, null)))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("steps[1].type"));
        assertThatThrownBy(() -> service.addStep(ANA, workflow.getId(), new NewStep(null, "teleport", null, null)))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("steps[1].type"));

        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void anEmptyStringClearsNameAndInputRef() {
        Workflow workflow = stored(draft(ANA, false));
        WorkflowStep step = withSteps(workflow, "echo", "echo").get(1);
        step.edit("Say hi", "{\"label\":\"x\"}", "{step:1.output}");

        WorkflowStepView edited = service.updateStep(ANA, workflow.getId(), step.getId(), new StepEdit("", null, ""));

        assertThat(step.getName()).isNull();
        assertThat(step.getInputRef()).isNull();
        assertThat(step.getConfig()).isEqualTo("{\"label\":\"x\"}");
        assertThat(edited.stepOrder()).isEqualTo(2);
    }

    @Test
    void aNullFieldLeavesTheStepUnchanged() {
        Workflow workflow = stored(draft(ANA, false));
        WorkflowStep step = withSteps(workflow, "echo", "echo").get(1);
        step.edit("Say hi", "{\"label\":\"x\"}", "{step:1.output}");

        service.updateStep(ANA, workflow.getId(), step.getId(), new StepEdit(null, "{\"profile\":\"standard\"}", null));

        assertThat(step.getName()).isEqualTo("Say hi");
        assertThat(step.getInputRef()).isEqualTo("{step:1.output}");
        assertThat(step.getConfig()).isEqualTo("{\"profile\":\"standard\"}");
        assertThat(step.getType()).isEqualTo("echo");
    }

    @Test
    void aStepOfAnotherWorkflowIsNotFound() {
        Workflow mine = stored(draft(ANA, false));
        withSteps(mine, "echo");
        Workflow theirs = draft(ANA, false);
        WorkflowStep foreign = withSteps(theirs, "echo").get(0);

        assertThatThrownBy(() -> service.updateStep(ANA, mine.getId(), foreign.getId(), new StepEdit("x", null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateStep(ANA, mine.getId(), UUID.randomUUID(),
                new StepEdit("x", null, null))).isInstanceOf(NotFoundException.class);
        assertThat(foreign.getName()).isEqualTo("s1");
    }

    @Test
    void theThirtyTwoThousandSevenHundredSixtyEighthStepIsRefused() {
        Workflow workflow = stored(draft(ANA, false));
        withSteps(workflow);
        when(workflowStepRepository.findMaxStepOrder(workflow.getId())).thenReturn((int) Short.MAX_VALUE);

        assertThatThrownBy(() -> service.addStep(ANA, workflow.getId(), new NewStep(null, "echo", null, null)))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getDetail().code()).isEqualTo("workflow.definitionStep.tooMany"));
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void aConfigThatIsNotAnObjectIsRefusedNamingConfig() {
        Workflow workflow = stored(draft(ANA, false));
        WorkflowStep step = withSteps(workflow, "echo").get(0);

        for (String config : List.of("[1,2]", "\"text\"", "7", "{broken", "")) {
            assertThatThrownBy(() -> service.addStep(ANA, workflow.getId(), new NewStep(null, "echo", null, config)))
                    .isInstanceOfSatisfying(UnprocessableEntityException.class,
                            e -> assertThat(e.getErrors()).containsOnlyKeys("config"));
            assertThatThrownBy(() -> service.updateStep(ANA, workflow.getId(), step.getId(),
                    new StepEdit(null, config, null)))
                    .isInstanceOfSatisfying(UnprocessableEntityException.class,
                            e -> assertThat(e.getErrors()).containsOnlyKeys("config"));
        }
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void aConfigOverTheCapIsRefusedAndOneAtTheCapIsAccepted() {
        Workflow workflow = stored(draft(ANA, false));
        WorkflowStep step = withSteps(workflow, "echo").get(0);
        String atCap = configOfLength(CONFIG_CAP);
        String overCap = configOfLength(CONFIG_CAP + 1);

        assertThatThrownBy(() -> service.updateStep(ANA, workflow.getId(), step.getId(),
                new StepEdit(null, overCap, null)))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("config"));
        assertThatThrownBy(() -> service.addStep(ANA, workflow.getId(), new NewStep(null, "echo", null, overCap)))
                .isInstanceOf(UnprocessableEntityException.class);

        service.updateStep(ANA, workflow.getId(), step.getId(), new StepEdit(null, atCap, null));
        assertThat(step.getConfig()).hasSize(CONFIG_CAP);
        service.addStep(ANA, workflow.getId(), new NewStep(null, "echo", null, atCap));
    }

    private static String configOfLength(int length) {
        String frame = "{\"a\":\"\"}";
        return "{\"a\":\"" + "x".repeat(length - frame.length()) + "\"}";
    }

    @Test
    void editingAConfigWhoseOutputInputsReadALaterStepIsRefusedAndNothingIsSaved() {
        Workflow workflow = stored(draft(ANA, false));
        WorkflowStep step = withSteps(workflow, "probe", "extract_audio", "extract_audio").get(1);
        String reading = "{\"outputInputs\":[{\"stepOrder\":3,\"outputName\":\"a.wav\",\"inputKey\":\"a\"}]}";

        assertThatThrownBy(() -> service.updateStep(ANA, workflow.getId(), step.getId(),
                new StepEdit("renamed", reading, null)))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("steps[1].config"));

        assertThat(step.getConfig()).isNull();
        assertThat(step.getName()).isEqualTo("s2");
        verify(workflowStepRepository, never()).save(any());
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
                                        + "echo, extract_audio, fail, generate_script, generate_speech, "
                                        + "generate_storyboard, generate_video, probe, render_video",
                                "steps[3].type unknown capability 'demucs-typo'; allowed: APPROVAL_CHECKPOINT, "
                                        + "align_audio, echo, extract_audio, fail, generate_script, "
                                        + "generate_speech, generate_storyboard, generate_video, probe, "
                                        + "render_video"));

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

    @Test
    void aNonOwnerCannotDeleteOrReorderSteps() {
        Workflow visible = stored(published(ANA, true));
        List<WorkflowStep> steps = withSteps(visible, "echo", "echo");
        Workflow hidden = stored(draft(ANA, true));
        withSteps(hidden, "echo");

        assertThatThrownBy(() -> service.deleteStep(BEN, visible.getId(), steps.get(0).getId()))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.reorderSteps(BEN, visible.getId(),
                List.of(steps.get(1).getId(), steps.get(0).getId())))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.deleteStep(BEN, hidden.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.reorderSteps(BEN, hidden.getId(), List.of()))
                .isInstanceOf(NotFoundException.class);

        verify(workflowStepRepository, never()).deleteById(any());
        verify(workflowStepRepository, never()).parkOrders(any(), any());
        verify(workflowStepRepository, never()).setOrder(any(), anyShort());
    }

    @Test
    void deletingAStepOfAnotherWorkflowIsNotFound() {
        Workflow workflow = stored(published(ANA, false));
        withSteps(workflow, "echo");

        assertThatThrownBy(() -> service.deleteStep(ANA, workflow.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);

        verify(workflowStepRepository, never()).deleteById(any());
    }

    @Test
    void deletingTheMediaFirstStepUnderAFileReadingStepIsRefusedAndNothingIsSaved() {
        Workflow workflow = stored(published(ANA, false));
        List<WorkflowStep> steps = withSteps(workflow, "extract_audio", "echo", "extract_audio");

        assertThatThrownBy(() -> service.deleteStep(ANA, workflow.getId(), steps.get(0).getId()))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("steps[1].type"));

        verify(workflowStepRepository, never()).deleteById(any());
        verify(workflowStepRepository, never()).setOrder(any(), anyShort());
    }

    @Test
    void aReorderThatMovesAnOutputInputsSourceAfterItsReaderIsRefusedAndNothingIsSaved() {
        Workflow workflow = stored(published(ANA, false));
        List<WorkflowStep> steps = withSteps(workflow, "extract_audio", "extract_audio");
        String config = "{\"outputInputs\":[" + entry("1", "\"a.wav\"", "\"a\"") + "]}";
        steps.set(1, new WorkflowStep(steps.get(1).getId(), workflow.getId(), (short) 2, "s2", "extract_audio", null,
                config));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(steps);

        assertThatThrownBy(() -> service.reorderSteps(ANA, workflow.getId(),
                List.of(steps.get(1).getId(), steps.get(0).getId())))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactly("steps[0].config"));

        verify(workflowStepRepository, never()).parkOrders(any(), any());
        verify(workflowStepRepository, never()).setOrder(any(), anyShort());
    }

    @Test
    void deletingAMiddleStepCompactsTheLaterOrders() {
        Workflow workflow = stored(published(ANA, false));
        List<WorkflowStep> steps = withSteps(workflow, "echo", "echo", "echo");
        steps.set(2, new WorkflowStep(steps.get(2).getId(), workflow.getId(), (short) 3, "s3", "echo", "{step:1.x}"));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId()))
                .thenReturn(steps, List.of(steps.get(0), steps.get(2)));

        service.deleteStep(ANA, workflow.getId(), steps.get(1).getId());

        verify(workflowStepRepository).deleteById(steps.get(1).getId());
        verify(workflowStepRepository).setOrder(steps.get(2).getId(), (short) 2);
        verify(workflowStepRepository, never()).parkOrders(any(), any());
    }

    @Test
    void aReaderOfTheDeletedStepRefusesTheDeleteNamingTheReaders() {
        Workflow workflow = stored(published(ANA, false));
        List<WorkflowStep> steps = withSteps(workflow, "echo", "echo", "echo");
        steps.set(1, new WorkflowStep(steps.get(1).getId(), workflow.getId(), (short) 2, "s2", "echo", "{step:1}"));
        steps.set(2, new WorkflowStep(steps.get(2).getId(), workflow.getId(), (short) 3, "s3", "echo", "{step:1.a}"));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(steps);

        assertThatThrownBy(() -> service.deleteStep(ANA, workflow.getId(), steps.get(0).getId()))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    LocalizedMessage message = e.getDetail();
                    assertThat(message.code()).isEqualTo("workflow.definitionStep.referenced");
                    assertThat(message.getArguments()).containsExactly(1, "2, 3");
                });

        verify(workflowStepRepository, never()).deleteById(any());
    }

    @Test
    void reorderRefusesADuplicateAStaleListAndAForwardReference() {
        Workflow workflow = stored(published(ANA, false));
        List<WorkflowStep> steps = withSteps(workflow, "echo", "echo");
        UUID first = steps.get(0).getId();
        UUID second = steps.get(1).getId();

        assertThatThrownBy(() -> service.reorderSteps(ANA, workflow.getId(), List.of(first, first)))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThatThrownBy(() -> service.reorderSteps(ANA, workflow.getId(), List.of(first)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.reorderSteps(ANA, workflow.getId(), List.of(first, UUID.randomUUID())))
                .isInstanceOf(ConflictException.class);

        steps.set(1, new WorkflowStep(second, workflow.getId(), (short) 2, "s2", "echo", "{step:1}"));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(steps);
        assertThatThrownBy(() -> service.reorderSteps(ANA, workflow.getId(), List.of(second, first)))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WORKFLOW_STEP_ORDER_INVALID");
                    assertThat(e.getErrors()).containsKey("steps[0].inputRef");
                });

        verify(workflowStepRepository, never()).parkOrders(any(), any());
    }

    @Test
    void reorderParksAndWritesOnlyTheMovedStepsAndTheSameOrderWritesNothing() {
        Workflow workflow = stored(published(ANA, false));
        List<WorkflowStep> steps = withSteps(workflow, "echo", "echo", "echo");
        UUID a = steps.get(0).getId();
        UUID b = steps.get(1).getId();
        UUID c = steps.get(2).getId();

        service.reorderSteps(ANA, workflow.getId(), List.of(a, b, c));
        verify(workflowStepRepository, never()).parkOrders(any(), any());

        service.reorderSteps(ANA, workflow.getId(), List.of(a, c, b));

        verify(workflowStepRepository).parkOrders(workflow.getId(), List.of(c, b));
        verify(workflowStepRepository).setOrder(c, (short) 2);
        verify(workflowStepRepository).setOrder(b, (short) 3);
        verify(workflowStepRepository, never()).setOrder(eq(a), anyShort());
    }

    private List<WorkflowStep> echoes(Workflow workflow, String[] names, String[] refs) {
        List<WorkflowStep> steps = withSteps(workflow, java.util.Collections.nCopies(names.length, "echo")
                .toArray(new String[0]));
        for (int i = 0; i < names.length; i++) {
            steps.get(i).edit(names[i], steps.get(i).getConfig(), refs[i]);
        }
        return steps;
    }

    private static void assertReferenceInvalid(Throwable e) {
        assertThat(e).isInstanceOfSatisfying(UnprocessableEntityException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo("WORKFLOW_STEP_REFERENCE_INVALID");
            assertThat(ex.getErrors()).containsKey("inputRef");
        });
    }

    @Test
    void appendingAStepWithABrokenReferenceIsRefusedOnInputRefAndNothingIsSaved() {
        Workflow workflow = stored(draft(ANA, false));
        echoes(workflow, new String[] {"draft", "draft"}, new String[] {null, null});
        Workflow single = stored(draft(ANA, false));
        echoes(single, new String[] {"draft"}, new String[] {null});

        for (String ref : List.of("{step:script.text}", "{step:draft}", "{step:3}", "{step:9}", "{step: 1}")) {
            assertThatThrownBy(() -> service.addStep(ANA, workflow.getId(), new NewStep(null, "echo", ref, null)))
                    .satisfies(WorkflowServiceImplTest::assertReferenceInvalid);
        }
        assertThatThrownBy(() -> service.addStep(ANA, single.getId(), new NewStep(null, "echo", "{step:2}", null)))
                .satisfies(WorkflowServiceImplTest::assertReferenceInvalid);
        Workflow empty = stored(draft(ANA, false));
        withSteps(empty);
        assertThatThrownBy(() -> service.addStep(ANA, empty.getId(), new NewStep(null, "echo", "{step:1}", null)))
                .satisfies(WorkflowServiceImplTest::assertReferenceInvalid);

        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void editingAStepToReadALaterStepIsRefused() {
        Workflow workflow = stored(draft(ANA, false));
        List<WorkflowStep> steps = echoes(workflow, new String[] {"draft", "voice", "mix"},
                new String[] {null, null, null});

        assertThatThrownBy(() -> service.updateStep(ANA, workflow.getId(), steps.get(1).getId(),
                new StepEdit(null, null, "{step:mix}")))
                .satisfies(WorkflowServiceImplTest::assertReferenceInvalid);

        assertThat(steps.get(1).getInputRef()).isNull();
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void aCleanNamedReferenceIsSavedAsWritten() {
        Workflow workflow = stored(draft(ANA, false));
        echoes(workflow, new String[] {"draft", "voice"}, new String[] {null, null});

        WorkflowStepView added = service.addStep(ANA, workflow.getId(),
                new NewStep(null, "echo", "{step:draft.title}", null));

        assertThat(added.inputRef()).isEqualTo("{step:draft.title}");
    }

    @Test
    void renamingOrClearingANameAnotherStepReadsIsAConflictNamingTheReaders() {
        Workflow workflow = stored(draft(ANA, false));
        List<WorkflowStep> steps = echoes(workflow, new String[] {"draft", "voice", "mix"},
                new String[] {null, null, "{step:draft}"});

        for (String renamed : List.of("outline", "")) {
            assertThatThrownBy(() -> service.updateStep(ANA, workflow.getId(), steps.get(0).getId(),
                    new StepEdit(renamed, null, null)))
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetail().args())
                            .containsExactly("draft", "3"));
        }
        assertThat(steps.get(0).getName()).isEqualTo("draft");
        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void renamingAStepNobodyReadsByNameIsAllowedAndKeepsPositionalReaders() {
        Workflow workflow = stored(draft(ANA, false));
        List<WorkflowStep> steps = echoes(workflow, new String[] {"draft", "voice"},
                new String[] {null, "{step:1}"});

        service.updateStep(ANA, workflow.getId(), steps.get(0).getId(), new StepEdit("outline", null, null));

        assertThat(steps.get(0).getName()).isEqualTo("outline");
        assertThat(steps.get(1).getInputRef()).isEqualTo("{step:1}");
    }

    @Test
    void appendingAStepWithANameAnotherStepReadsIsAConflict() {
        Workflow workflow = stored(draft(ANA, false));
        echoes(workflow, new String[] {"draft", "voice"}, new String[] {null, "{step:draft}"});

        assertThatThrownBy(() -> service.addStep(ANA, workflow.getId(), new NewStep("draft", "echo", null, null)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetail().args())
                        .containsExactly("draft", "2"));

        verify(workflowStepRepository, never()).save(any());
    }

    @Test
    void aPreExistingBrokenReferenceDoesNotBlockAnUnrelatedWrite() {
        Workflow workflow = stored(draft(ANA, false));
        List<WorkflowStep> steps = echoes(workflow, new String[] {"a", "b"}, new String[] {null, "{step:5.output}"});

        service.updateStep(ANA, workflow.getId(), steps.get(0).getId(), new StepEdit("renamed", null, null));
        service.addStep(ANA, workflow.getId(), new NewStep(null, "echo", null, null));
        service.updateStep(ANA, workflow.getId(), steps.get(1).getId(),
                new StepEdit(null, "{\"label\":\"x\"}", null));
        service.updateStep(ANA, workflow.getId(), steps.get(1).getId(),
                new StepEdit(null, "{\"label\":\"y\"}", "{step:5.output}"));

        assertThat(steps.get(1).getInputRef()).isEqualTo("{step:5.output}");
        assertThat(steps.get(1).getConfig()).isEqualTo("{\"label\":\"y\"}");
    }

    private static NewWorkflowStep remote(String name, String outputInputs) {
        return new NewWorkflowStep(name, "extract_audio", null, "{\"outputInputs\":" + outputInputs + "}");
    }

    private static String entry(String stepOrder, String outputName, String inputKey) {
        return "{\"stepOrder\":" + stepOrder + ",\"outputName\":" + outputName + ",\"inputKey\":" + inputKey + "}";
    }
}
