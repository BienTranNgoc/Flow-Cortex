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
    private final StepExecutorRegistry registry =
            new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC());
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
                                "steps[1].type unknown capability 'transcode'; allowed: APPROVAL_CHECKPOINT, echo, "
                                        + "extract_audio, fail, probe",
                                "steps[3].type unknown capability 'demucs-typo'; allowed: APPROVAL_CHECKPOINT, echo, "
                                        + "extract_audio, fail, probe"));

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
    void createWorkflowKeepsTheInputsAStepDeclaresFromTheUploadAndEarlierSteps() {
        when(workflowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String config = "{\"inputs\":{\"media\":\"upload\",\"details\":\"step:1/video-details.json\","
                + "\"audio\":\"step:2/*\"},\"label\":\"x\"}";

        service.createWorkflow(UUID.randomUUID(), "Declared inputs", false, List.of(
                new NewWorkflowStep("probe", "probe", null),
                new NewWorkflowStep("extract", "extract_audio", null),
                new NewWorkflowStep("again", "extract_audio", null, config)));

        org.mockito.ArgumentCaptor<List<WorkflowStep>> saved = org.mockito.ArgumentCaptor.captor();
        verify(workflowStepRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(WorkflowStep::getConfig).containsExactly(null, null, config);
    }

    @Test
    void createWorkflowRefusesAnInputSourceThatIsNotAnEarlierStepOrNotWellFormedAtThatStepsConfig() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("probe", "probe", null),
                new NewWorkflowStep("self", "extract_audio", null, "{\"inputs\":{\"x\":\"step:2/a.wav\"}}"),
                new NewWorkflowStep("forward", "extract_audio", null, "{\"inputs\":{\"x\":\"step:5/x.wav\"}}"),
                new NewWorkflowStep("malformed", "extract_audio", null, "{\"inputs\":{\"x\":\"media\"}}"),
                new NewWorkflowStep("no slash", "extract_audio", null, "{\"inputs\":{\"x\":\"step:1\"}}"),
                new NewWorkflowStep("not text", "extract_audio", null, "{\"inputs\":{\"x\":1}}"),
                new NewWorkflowStep("not a map", "extract_audio", null, "{\"inputs\":[\"upload\"]}"),
                new NewWorkflowStep("not json", "extract_audio", null, "{inputs"));

        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Bad inputs", false, steps))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath().toString())
                        .containsExactlyInAnyOrder("steps[1].config", "steps[2].config", "steps[3].config",
                                "steps[4].config", "steps[5].config", "steps[6].config", "steps[7].config"));

        verify(workflowRepository, never()).save(any());
        verify(workflowStepRepository, never()).saveAll(any());
    }

    @Test
    void createWorkflowRefusesInputsOnAStepThatDoesNotRunRemotelyAtThatStepsConfig() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("probe", "probe", null),
                new NewWorkflowStep("echo", "echo", null, "{\"inputs\":{\"media\":\"upload\"}}"),
                new NewWorkflowStep("review", "APPROVAL_CHECKPOINT", null, "{\"inputs\":{\"d\":\"step:1/*\"}}"),
                new NewWorkflowStep("no inputs", "echo", null, "{\"label\":\"x\"}"));

        assertThatThrownBy(() -> service.createWorkflow(UUID.randomUUID(), "Inline inputs", false, steps))
                .isInstanceOfSatisfying(ConstraintViolationException.class, e -> assertThat(e.getConstraintViolations())
                        .extracting(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .containsExactlyInAnyOrder(
                                "steps[1].config only a remote step reads declared inputs; 'echo' runs inline",
                                "steps[2].config only a remote step reads declared inputs; 'APPROVAL_CHECKPOINT' "
                                        + "runs inline"));

        verify(workflowRepository, never()).save(any());
    }
}
