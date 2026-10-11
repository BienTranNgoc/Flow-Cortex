package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.domain.ProductVideoInput;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.ProductVideoInputRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProductVideoServiceTest {

    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final WorkflowStepRepository workflowSteps = mock(WorkflowStepRepository.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunStepRepository runSteps = mock(RunStepRepository.class);
    private final ProductVideoInputRepository inputs = mock(ProductVideoInputRepository.class);
    private final MediaJobs media = mock(MediaJobs.class);
    private final StepExecutorRegistry registry = new StepExecutorRegistry(media, Clock.systemUTC());
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));

    private ProductVideoService service(boolean enabled) {
        return new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs, media, registry, events,
                transactions, enabled, true);
    }

    @Test
    void disabledFlagCreatesNothing() {
        assertThatThrownBy(() -> service(false).start(UUID.randomUUID(), draft(), List.of(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(503));

        verify(media, never()).resolveImageUploads(any(), any());
        verify(runs, never()).save(any());
    }

    @Test
    void snapshotsConfirmedMetadataAndOrderedAssets() {
        UUID ownerId = UUID.randomUUID();
        UUID firstUpload = UUID.randomUUID();
        UUID secondUpload = UUID.randomUUID();
        Workflow workflow = new Workflow(ProductVideoService.WORKFLOW_ID, UUID.randomUUID(), "Product Video", true);
        List<WorkflowStep> definitions = List.of(
                step(1, "generate_script"),
                step(2, "APPROVAL_CHECKPOINT"),
                step(3, "generate_speech"),
                step(4, "render_video"));
        List<MediaAssetInput> assets = List.of(
                new MediaAssetInput(firstUpload, new MediaJobInput("image_1", "uploads", "first")),
                new MediaAssetInput(secondUpload, new MediaJobInput("image_2", "uploads", "second")));
        when(media.isEnabled()).thenReturn(true);
        when(workflows.findVisibleTo(ProductVideoService.WORKFLOW_ID, ownerId)).thenReturn(Optional.of(workflow));
        when(workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoService.WORKFLOW_ID))
                .thenReturn(definitions);
        when(media.resolveImageUploads(ownerId, List.of(firstUpload, secondUpload))).thenReturn(assets);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runSteps.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var started = service(true).start(ownerId, draft(), List.of(firstUpload, secondUpload));

        ArgumentCaptor<WorkflowRun> run = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runs).save(run.capture());
        assertThat(run.getValue().getInput())
                .contains("\"productName\":\"Bình giữ nhiệt\"")
                .contains("\"price\":\"299.000đ\"")
                .doesNotContain("claim");
        verify(inputs).save(any(ProductVideoInput.class));
        verify(media).bindRunInputs(run.getValue().getId(), assets);
        ArgumentCaptor<List<RunStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(runSteps).saveAll(steps.capture());
        assertThat(steps.getValue()).hasSize(4);
        assertThat(steps.getValue().get(0).getInput()).isNull();
        assertThat(steps.getValue().get(3).getInput()).contains("image_1", "image_2");
        assertThat(started.steps()).hasSize(4);
        verify(events).publishEvent(any(WorkflowRunStartedEvent.class));
    }

    private static WorkflowStep step(int order, String type) {
        return new WorkflowStep(UUID.randomUUID(), ProductVideoService.WORKFLOW_ID, (short) order,
                "step-" + order, type, null);
    }

    private static ProductVideoDraft draft() {
        return new ProductVideoDraft("Bình giữ nhiệt", "Giữ đồ uống tiện lợi", "Mua ngay", "299.000đ", null,
                "9:16");
    }
}
