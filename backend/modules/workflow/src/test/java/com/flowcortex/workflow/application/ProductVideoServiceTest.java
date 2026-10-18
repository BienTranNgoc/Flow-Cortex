package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard;
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
import java.util.ArrayList;
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
                transactions, new RunStartGuards(List.of()), enabled, true);
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
    void aRefusingStartGuardStopsTheRunBeforeUploadsAreResolved() {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = new Workflow(ProductVideoWorkflow.ID, UUID.randomUUID(), "Product Video", true);
        when(media.isEnabled()).thenReturn(true);
        when(workflows.findVisibleTo(ProductVideoWorkflow.ID, ownerId)).thenReturn(Optional.of(workflow));
        when(workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.ID))
                .thenReturn(List.of(step(1, "generate_script")));
        List<Boolean> acknowledged = new ArrayList<>();
        RunStartGuard refusing = (owner, workflowId, steps, context) -> {
            acknowledged.add(context.acknowledgeCost());
            throw new IllegalStateException("refused");
        };
        ProductVideoService service = new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs, media,
                registry, events, transactions, new RunStartGuards(List.of(refusing)), true, true);

        assertThatThrownBy(() -> service.start(ownerId, draft(), List.of(UUID.randomUUID()), true))
                .hasMessage("refused");

        assertThat(acknowledged).containsExactly(true);
        verify(media, never()).resolveImageUploads(any(), any());
        verify(runs, never()).save(any());
    }

    @Test
    void snapshotsConfirmedMetadataAndOrderedAssets() {
        UUID ownerId = UUID.randomUUID();
        UUID firstUpload = UUID.randomUUID();
        UUID secondUpload = UUID.randomUUID();
        Workflow workflow = new Workflow(ProductVideoWorkflow.ID, UUID.randomUUID(), "Product Video", true);
        List<WorkflowStep> definitions = List.of(
                step(1, "generate_script"),
                step(2, "APPROVAL_CHECKPOINT"),
                configured(3, "generate_speech", "{\"profile\":\"vi_voice_v1\"}", "{step:2}"),
                step(4, "render_video"),
                configured(5, "generate_video",
                        "{\"profile\":\"vertical_ad_clip_v1\",\"referenceImage\":\"image_1\"}", "{step:4}"));
        List<MediaAssetInput> assets = List.of(
                new MediaAssetInput(firstUpload, new MediaJobInput("image_1", "uploads", "first")),
                new MediaAssetInput(secondUpload, new MediaJobInput("image_2", "uploads", "second")));
        when(media.isEnabled()).thenReturn(true);
        when(workflows.findVisibleTo(ProductVideoWorkflow.ID, ownerId)).thenReturn(Optional.of(workflow));
        when(workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.ID))
                .thenReturn(definitions);
        when(media.resolveImageUploads(ownerId, List.of(firstUpload, secondUpload))).thenReturn(assets);
        List<MediaAssetInput> copies = List.of(
                new MediaAssetInput(firstUpload, new MediaJobInput("image_1", "media", "runs/x/inputs/image_1")),
                new MediaAssetInput(secondUpload, new MediaJobInput("image_2", "media", "runs/x/inputs/image_2")));
        when(media.copyRunInputs(any(), eq(assets))).thenReturn(copies);
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
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(media, runs);
        order.verify(media).copyRunInputs(run.getValue().getId(), assets);
        order.verify(runs).save(any());
        order.verify(media).bindRunInputs(run.getValue().getId(), copies);
        ArgumentCaptor<List<RunStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(runSteps).saveAll(steps.capture());
        assertThat(steps.getValue()).hasSize(5);
        assertThat(steps.getValue().get(0).getInput()).contains("image_1", "image_2");
        assertThat(steps.getValue().get(2).getInput()).isNull();
        assertThat(steps.getValue().get(3).getInput()).contains("image_1", "image_2");
        assertThat(steps.getValue().get(4).getInput())
                .isEqualTo(steps.getValue().get(3).getInput())
                .contains("runs/x/inputs/image_1");
        assertThat(steps.getValue().get(2).getConfig()).isEqualTo("{\"profile\":\"vi_voice_v1\"}");
        assertThat(steps.getValue().get(2).getInputRef()).isEqualTo("{step:2}");
        assertThat(steps.getValue().get(0).getConfig()).isNull();
        assertThat(started.steps()).hasSize(5);
        verify(events).publishEvent(any(WorkflowRunStartedEvent.class));
    }

    private static WorkflowStep step(int order, String type) {
        return new WorkflowStep(UUID.randomUUID(), ProductVideoWorkflow.ID, (short) order,
                "step-" + order, type, null);
    }

    /** A definition with step settings; the entity has no config setter outside persistence. */
    private static WorkflowStep configured(int order, String type, String config, String inputRef) {
        WorkflowStep definition = mock(WorkflowStep.class);
        when(definition.getStepOrder()).thenReturn((short) order);
        when(definition.getType()).thenReturn(type);
        when(definition.getConfig()).thenReturn(config);
        when(definition.getInputRef()).thenReturn(inputRef);
        return definition;
    }

    private static ProductVideoDraft draft() {
        return new ProductVideoDraft("Bình giữ nhiệt", "Giữ đồ uống tiện lợi", "Mua ngay", "299.000đ", null,
                "9:16");
    }
}
