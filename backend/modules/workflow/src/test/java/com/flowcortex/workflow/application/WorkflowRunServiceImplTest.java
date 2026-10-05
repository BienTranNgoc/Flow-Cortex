package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link WorkflowRunServiceImpl}: start-run tx#1, the kill switch, and
 * owner-scoped reads.
 */
class WorkflowRunServiceImplTest {

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowStepRepository workflowStepRepository = mock(WorkflowStepRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MediaJobs mediaJobs = mock(MediaJobs.class);
    private final StepExecutorRegistry registry = new StepExecutorRegistry(mediaJobs, Clock.systemUTC());
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private WorkflowRunServiceImpl newService(boolean workflowEnabled) {
        return new WorkflowRunServiceImpl(workflowRepository, workflowStepRepository, workflowRunRepository,
                runStepRepository, events, registry, mediaJobs, mock(UserDirectory.class),
                new TransactionTemplate(transactions), workflowEnabled);
    }

    private Workflow givenWorkflow(UUID ownerId, String... types) {
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "Demo", false);
        List<WorkflowStep> steps = new ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            steps.add(new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) (i + 1), "s" + i, types[i], null));
        }
        when(workflowRepository.findVisibleTo(workflow.getId(), ownerId)).thenReturn(Optional.of(workflow));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId())).thenReturn(steps);
        when(workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return workflow;
    }

    @Test
    void startRunWithARemoteStepWhileMediaIsDisabledThrows503AndCreatesNothing() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "probe", "echo");
        when(mediaJobs.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.startRun(ownerId, workflow.getId(), "hi"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException status = (ResponseStatusException) e;
                    assertThat(status.getStatusCode().value()).isEqualTo(503);
                    assertThat(status.getReason()).isEqualTo("Media engine is currently disabled");
                });

        verify(workflowRunRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void startRunWithOnlyInlineStepsIgnoresTheMediaSwitch() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "echo", "fail");
        when(mediaJobs.isEnabled()).thenReturn(false);

        assertThat(service.startRun(ownerId, workflow.getId(), "hi").steps()).hasSize(2);
    }

    @Test
    void startRunWhenDisabledThrows503AndCreatesNothing() {
        WorkflowRunServiceImpl service = newService(false);

        assertThatThrownBy(() -> service.startRun(UUID.randomUUID(), UUID.randomUUID(), "hi"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(503));

        verify(workflowRunRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void startRunOnInvisibleWorkflowIsNotFound() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findVisibleTo(workflowId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startRun(ownerId, workflowId, "hi")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void startRunCreatesPendingStepsForEachDefinitionStepAndPublishesEvent() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "Demo", false);
        WorkflowStep step1 = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "first", "echo", null);
        WorkflowStep step2 = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 2, "second", "echo", null);
        when(workflowRepository.findVisibleTo(workflow.getId(), ownerId)).thenReturn(Optional.of(workflow));
        when(workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId()))
                .thenReturn(List.of(step1, step2));
        when(workflowRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runStepRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RunView started = service.startRun(ownerId, workflow.getId(), "hello");

        assertThat(started.status()).isEqualTo("running");
        assertThat(started.ownerId()).isEqualTo(ownerId);
        assertThat(started.input()).isEqualTo("hello");
        assertThat(started.steps()).hasSize(2);
        assertThat(started.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("pending"));
        verify(events).publishEvent(any(WorkflowRunStartedEvent.class));
    }

    @Test
    void aRemoteFirstStepWithoutAnUploadIsRefusedAndCreatesNothing() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "probe");
        when(mediaJobs.isEnabled()).thenReturn(true);

        assertThatThrownBy(() -> service.startRun(ownerId, workflow.getId(), null, null, "clip.mp4"))
                .isInstanceOfSatisfying(UploadRejectedException.class, e -> {
                    assertThat(e.field()).isEqualTo("uploadId");
                    assertThat(e.getMessage()).isEqualTo("Choose a video or audio file to upload");
                });

        verify(workflowRunRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void anUploadTheMediaModuleCannotResolveForTheCallerIsRefusedAndCreatesNothing() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "probe");
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.resolveUpload(ownerId, uploadId))
                .thenThrow(new UploadRejectedException("uploadId", "Upload the file again"));

        assertThatThrownBy(() -> service.startRun(ownerId, workflow.getId(), null, uploadId, "clip.mp4"))
                .isInstanceOfSatisfying(UploadRejectedException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("Upload the file again"));

        verify(workflowRunRepository, never()).save(any());
        verify(runStepRepository, never()).saveAll(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void aResolvedUploadMakesTheRunKeepTheCleanedFileName() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "probe");
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.resolveUpload(ownerId, uploadId))
                .thenReturn(new MediaJobInput("media", "flowcortex-uploads", ownerId + "/" + uploadId));

        RunView started = service.startRun(ownerId, workflow.getId(), "{\"bucket\":\"elsewhere\"}", uploadId,
                "../a\u0007b.mp4");

        assertThat(started.input()).isEqualTo("..ab.mp4");
    }

    @Test
    void oneResolvedUploadBecomesTheInputOfEveryRemoteStep() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "probe", "extract_audio", "echo");
        MediaJobInput input = new MediaJobInput(
                "media", "flowcortex-uploads", ownerId + "/" + uploadId);
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.resolveUpload(ownerId, uploadId)).thenReturn(input);

        service.startRun(ownerId, workflow.getId(), null, uploadId, "clip.mp4");

        List<RunStep> steps = List.copyOf(savedSteps());
        String expected = "{\"key\":\"media\",\"bucket\":\"flowcortex-uploads\","
                + "\"objectKey\":\"" + ownerId + "/" + uploadId + "\"}";
        assertThat(steps).extracting(RunStep::getInput)
                .containsExactly(expected, expected, null);
        verify(mediaJobs).resolveUpload(ownerId, uploadId);
    }

    @Test
    void theUploadIsLookedUpInTheObjectStoreBeforeTheRunTransactionOpens() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "probe");
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.resolveUpload(ownerId, uploadId))
                .thenReturn(new MediaJobInput("media", "flowcortex-uploads", ownerId + "/" + uploadId));

        service.startRun(ownerId, workflow.getId(), null, uploadId, "clip.mp4");

        InOrder order = inOrder(mediaJobs, transactions, workflowRunRepository);
        order.verify(mediaJobs).resolveUpload(ownerId, uploadId);
        order.verify(transactions).getTransaction(any());
        order.verify(workflowRunRepository).save(any());
    }

    @Test
    void aLongFileNameIsTrimmedTo255Characters() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "probe");
        when(mediaJobs.isEnabled()).thenReturn(true);
        when(mediaJobs.resolveUpload(ownerId, uploadId))
                .thenReturn(new MediaJobInput("media", "flowcortex-uploads", ownerId + "/" + uploadId));

        RunView started = service.startRun(ownerId, workflow.getId(), null, uploadId, "x".repeat(296) + ".mp4");

        assertThat(started.input()).hasSize(255);
    }

    @Test
    void anInlineFirstStepKeepsItsTextInputAndNeverLooksAnUploadUp() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = givenWorkflow(ownerId, "echo");

        RunView started = service.startRun(ownerId, workflow.getId(), "hello", null, null);

        assertThat(started.input()).isEqualTo("hello");
        assertThat(savedSteps()).singleElement().extracting(RunStep::getInput).isNull();
        verify(mediaJobs, never()).resolveUpload(any(), any());
    }

    @SuppressWarnings("unchecked")
    private Collection<RunStep> savedSteps() {
        ArgumentCaptor<Collection<RunStep>> steps = ArgumentCaptor.forClass(Collection.class);
        verify(runStepRepository).saveAll(steps.capture());
        return steps.getValue();
    }

    @Test
    void getRunForeignRunIsNotFound() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(workflowRunRepository.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRun(ownerId, runId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getRunReturnsTheRunWithItsSteps() {
        WorkflowRunServiceImpl service = newService(true);
        UUID ownerId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), ownerId, "in", Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        when(workflowRunRepository.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.of(run));
        when(runStepRepository.findByRunIdOrderByStepOrderAsc(run.getId())).thenReturn(List.of(step));

        RunView view = service.getRun(ownerId, run.getId());

        assertThat(view.id()).isEqualTo(run.getId());
        assertThat(view.steps()).hasSize(1);
    }
}
