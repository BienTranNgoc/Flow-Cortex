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
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard;
import com.flowcortex.workflow.domain.ProductVideoInput;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.ProductVideoInputRepository;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
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
    private final ReviewTaskRepository reviewTasks = mock(ReviewTaskRepository.class);
    private final MediaJobs media = mock(MediaJobs.class);
    private final StepExecutorRegistry registry = new StepExecutorRegistry(media, Clock.systemUTC());
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RunUpdates runUpdates = mock(RunUpdates.class);
    private final TransactionTemplate transactions = new TransactionTemplate(mock(PlatformTransactionManager.class));

    private ProductVideoService service(boolean enabled) {
        return new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs, media, registry, events,
                runUpdates, transactions, new RunStartGuards(List.of()), Tracer.NOOP, enabled, true);
    }

    @Test
    void disabledFlagCreatesNothing() {
        assertThatThrownBy(() -> service(false).start(UUID.randomUUID(), draft(), List.of(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(503));

        verify(media, never()).resolveImageUploads(any(), any(), any());
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
                registry, events, runUpdates, transactions, new RunStartGuards(List.of(refusing)), Tracer.NOOP, true, true);

        assertThatThrownBy(() -> service.start(ownerId, draft(), List.of(UUID.randomUUID()), true))
                .hasMessage("refused");

        assertThat(acknowledged).containsExactly(true);
        verify(media, never()).resolveImageUploads(any(), any(), any());
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
        when(media.resolveImageUploads(UploadPurpose.RUN_INPUT, ownerId, List.of(firstUpload, secondUpload))).thenReturn(assets);
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
        verify(runUpdates).touched(started.id());
    }

    @Test
    void refusesMcTemplateWhenFeatureFlagIsDisabled() {
        UUID ownerId = UUID.randomUUID();
        when(media.isEnabled()).thenReturn(true);
        ProductVideoDraft mcDraft = ProductVideoDraft.of("Đồng hồ", "Chống nước", null, null, null, "9:16",
                "mc-v1", "female", "mc-nu-tre", null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service(true).start(ownerId, mcDraft, List.of(UUID.randomUUID())))
                .isInstanceOf(com.flowcortex.shared.web.LocalizedStatusException.class);
    }

    @Test
    void refusesMcTemplateWhenWorkflowLacksMcStepsEvenIfFlagEnabled() {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = mock(Workflow.class);
        when(workflow.getId()).thenReturn(ProductVideoWorkflow.MC_ID);
        when(media.isEnabled()).thenReturn(true);
        when(workflows.findVisibleTo(ProductVideoWorkflow.MC_ID, ownerId)).thenReturn(Optional.of(workflow));
        when(workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.MC_ID))
                .thenReturn(List.of(step(1, "generate_script"), step(2, "generate_video")));
        ProductVideoDraft mcDraft = ProductVideoDraft.of("Đồng hồ", "Chống nước", null, null, null, "9:16",
                "mc-v1", "female", "mc-nu-tre", null, null);
        ProductVideoService mcEnabledService = new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs,
                media, registry, events, runUpdates, transactions, new RunStartGuards(List.of()), Tracer.NOOP,
                true, true, true);
        assertThatThrownBy(() -> mcEnabledService.start(ownerId, mcDraft, List.of(UUID.randomUUID())))
                .isInstanceOf(LocalizedStatusException.class);
    }

    @Test
    void refusesMcTemplateWithoutMcSourceOrWithInvalidPreset() {
        UUID ownerId = UUID.randomUUID();
        when(media.isEnabled()).thenReturn(true);
        ProductVideoService mcEnabledService = new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs,
                media, registry, events, runUpdates, transactions, new RunStartGuards(List.of()), Tracer.NOOP,
                true, true, true);
        ProductVideoDraft noMc = ProductVideoDraft.of("Đồng hồ", "Chống nước", null, null, null, "9:16",
                "mc-v1", "female", null, null, null);
        assertThatThrownBy(() -> mcEnabledService.start(ownerId, noMc, List.of(UUID.randomUUID())))
                .isInstanceOf(LocalizedStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(400));

        ProductVideoDraft badPreset = ProductVideoDraft.of("Đồng hồ", "Chống nước", null, null, null, "9:16",
                "mc-v1", "female", "mc-khong-ton-tai", null, null);
        assertThatThrownBy(() -> mcEnabledService.start(ownerId, badPreset, List.of(UUID.randomUUID())))
                .isInstanceOf(LocalizedStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(400));
    }

    @Test
    void preservesMcTemplateFemaleVoiceCustomUploadsAndSceneNoteWithDistinctKeys() {
        UUID ownerId = UUID.randomUUID();
        UUID productUpload = UUID.randomUUID();
        UUID mcUpload = UUID.randomUUID();
        UUID bgUpload = UUID.randomUUID();
        Workflow workflow = mock(Workflow.class);
        when(workflow.getId()).thenReturn(ProductVideoWorkflow.MC_ID);
        List<WorkflowStep> definitions = List.of(
                step(1, "generate_script"),
                configured(2, "generate_storyboard", "{\"profile\":\"product_ad_storyboard_v1\"}", "{step:1}"),
                configured(3, "generate_speech", "{\"profile\":\"vi_voice_v1\"}", "{step:1}"),
                configured(4, "generate_keyframe",
                        "{\"profile\":\"vertical_ad_keyframe_v1\",\"referenceImages\":[\"mc\",\"image_1\",\"background\"]}",
                        "{step:2}"),
                step(5, "lipsync_video"),
                step(6, "render_video"));
        MediaAssetInput pAsset = new MediaAssetInput(productUpload, new MediaJobInput("image_1", "uploads", "p1"));
        MediaAssetInput mcAsset = new MediaAssetInput(mcUpload, new MediaJobInput("image_1", "uploads", "mc"));
        MediaAssetInput bgAsset = new MediaAssetInput(bgUpload, new MediaJobInput("image_1", "uploads", "bg"));
        MediaAssetInput mcRekeyed = new MediaAssetInput(mcUpload, new MediaJobInput("mc", "uploads", "mc"));
        MediaAssetInput bgRekeyed = new MediaAssetInput(bgUpload, new MediaJobInput("background", "uploads", "bg"));
        when(media.isEnabled()).thenReturn(true);
        when(workflows.findVisibleTo(ProductVideoWorkflow.MC_ID, ownerId)).thenReturn(Optional.of(workflow));
        when(workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.MC_ID)).thenReturn(definitions);
        when(media.resolveImageUploads(UploadPurpose.RUN_INPUT, ownerId, List.of(productUpload)))
                .thenReturn(List.of(pAsset));
        when(media.resolveImageUploads(UploadPurpose.RUN_INPUT, ownerId, List.of(mcUpload)))
                .thenReturn(List.of(mcAsset));
        when(media.resolveImageUploads(UploadPurpose.RUN_INPUT, ownerId, List.of(bgUpload)))
                .thenReturn(List.of(bgAsset));
        when(media.copyRunInputs(any(), eq(List.of(pAsset, mcRekeyed, bgRekeyed))))
                .thenReturn(List.of(pAsset, mcRekeyed, bgRekeyed));
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runSteps.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductVideoDraft mcDraft = ProductVideoDraft.of("Đồng hồ", "Chống nước", null, null, null, "9:16",
                "mc-v1", "female", null, null, "Quầy gỗ ánh nắng");
        ProductVideoService mcEnabledService = new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs,
                media, registry, events, runUpdates, transactions, new RunStartGuards(List.of()), Tracer.NOOP,
                true, true, true);
        mcEnabledService.start(ownerId, mcDraft, List.of(productUpload), mcUpload, bgUpload, false, null);

        ArgumentCaptor<WorkflowRun> run = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runs).save(run.capture());
        assertThat(run.getValue().getWorkflowId()).isEqualTo(ProductVideoWorkflow.MC_ID);
        assertThat(run.getValue().getInput())
                .contains("\"templateId\":\"mc-v1\"")
                .contains("\"sceneNote\":\"Quầy gỗ ánh nắng\"")
                .doesNotContain("voiceGender")
                .doesNotContain("mcPresetId")
                .doesNotContain("hasCustomMc");
        ArgumentCaptor<List<RunStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(runSteps).saveAll(steps.capture());
        assertThat(steps.getValue().get(1).getConfig()).contains("\"templateId\":\"mc-v1\"").contains("\"sceneNote\"");
        assertThat(steps.getValue().get(2).getConfig()).contains("\"voiceGender\":\"female\"");
        assertThat(steps.getValue().get(3).getConfig()).contains("\"sceneNote\":\"Quầy gỗ ánh nắng\"");
        assertThat(steps.getValue().get(5).getInput()).contains("image_1").doesNotContain("background");
    }

    @Test
    void storesBuiltInPresetImagesForMcTemplate() {
        UUID ownerId = UUID.randomUUID();
        UUID productUpload = UUID.randomUUID();
        Workflow workflow = mock(Workflow.class);
        when(workflow.getId()).thenReturn(ProductVideoWorkflow.MC_ID);
        List<WorkflowStep> definitions = List.of(
                step(1, "generate_script"),
                configured(2, "generate_keyframe",
                        "{\"profile\":\"vertical_ad_keyframe_v1\",\"referenceImages\":[\"mc\",\"image_1\",\"background\"]}",
                        "{step:1}"),
                step(3, "lipsync_video"));
        MediaAssetInput pAsset = new MediaAssetInput(productUpload, new MediaJobInput("image_1", "uploads", "p1"));
        MediaAssetInput pCopy = new MediaAssetInput(productUpload, new MediaJobInput("image_1", "media", "runs/r/inputs/image_1"));
        MediaJobInput mcObj = new MediaJobInput("mc", "media", "runs/r/inputs/mc");
        MediaJobInput bgObj = new MediaJobInput("background", "media", "runs/r/inputs/background");
        when(media.isEnabled()).thenReturn(true);
        when(workflows.findVisibleTo(ProductVideoWorkflow.MC_ID, ownerId)).thenReturn(Optional.of(workflow));
        when(workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.MC_ID)).thenReturn(definitions);
        when(media.resolveImageUploads(UploadPurpose.RUN_INPUT, ownerId, List.of(productUpload)))
                .thenReturn(List.of(pAsset));
        when(media.copyRunInputs(any(), eq(List.of(pAsset)))).thenReturn(List.of(pCopy));
        when(media.putRunInputImageObject(any(), eq("mc"), any())).thenReturn(mcObj);
        when(media.putRunInputImageObject(any(), eq("background"), any())).thenReturn(bgObj);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runSteps.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductVideoDraft mcDraft = ProductVideoDraft.of("Đồng hồ", "Chống nước", null, null, null, "9:16",
                "mc-v1", "female", "mc-nu-tre", "bg-studio-minimal", "Ánh sáng mềm");
        ProductVideoPresetCatalog stubCatalog = stubPresetCatalog();
        ProductVideoService mcEnabledService = new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs,
                reviewTasks, stubCatalog, media, registry, events, runUpdates, transactions,
                new RunStartGuards(List.of()), Tracer.NOOP, true, true, true);
        mcEnabledService.start(ownerId, mcDraft, List.of(productUpload), null, null, false, null);

        verify(media).putRunInputImageObject(any(), eq("mc"), any());
        verify(media).putRunInputImageObject(any(), eq("background"), any());
        verify(media).replaceRunInputBinding(any(), eq(ownerId), eq(mcObj), eq(4L));
        verify(media).replaceRunInputBinding(any(), eq(ownerId), eq(bgObj), eq(4L));
        ArgumentCaptor<List<RunStep>> steps = ArgumentCaptor.forClass(List.class);
        verify(runSteps).saveAll(steps.capture());
        assertThat(steps.getValue().get(1).getInput())
                .contains("\"key\":\"mc\"")
                .contains("\"key\":\"background\"")
                .contains("\"key\":\"image_1\"");
        assertThat(steps.getValue().get(1).getConfig())
                .contains("\"scenePreset\":\"bg-studio-minimal\"")
                .contains("\"sceneNote\":\"Ánh sáng mềm\"");
    }

    @Test
    void regeneratesKeyframeAtCheckpoint2AndResumesRun() {
        UUID ownerId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        WorkflowRun run = new WorkflowRun(runId, ProductVideoWorkflow.MC_ID, ownerId, "{}", now);
        run.markWaitingApproval();

        RunStep keyframeStep = new RunStep(UUID.randomUUID(), runId, (short) 5, "generate_keyframe",
                "[{\"key\":\"mc\",\"bucket\":\"media\",\"objectKey\":\"runs/r/inputs/mc\"}]",
                "{\"profile\":\"vertical_ad_keyframe_v1\",\"referenceImages\":[\"mc\",\"image_1\",\"background\"]}",
                "{step:4}");
        keyframeStep.markRunning(now);
        // Simulate RunAdvancer overwriting RunStep.input with the resolved storyboard JSON on attempt 1.
        keyframeStep.markCompleted("{\"scenes\":[{\"index\":1,\"visualPrompt\":\"MC holds product\"}]}",
                "{\"keyframe\":\"old\"}", now);

        RunStep checkpointStep = new RunStep(UUID.randomUUID(), runId, (short) 6, HumanSteps.APPROVAL_CHECKPOINT,
                null, "{\"kind\":\"keyframe_review\"}", "{step:5}");
        RunStep clip1Step = new RunStep(UUID.randomUUID(), runId, (short) 7, "generate_video",
                "[{\"key\":\"image_1\",\"bucket\":\"media\",\"objectKey\":\"runs/" + runId + "/inputs/image_1\"},"
                        + "{\"key\":\"background\",\"bucket\":\"media\",\"objectKey\":\"runs/" + runId + "/inputs/background\"}]",
                "{\"profile\":\"vertical_ad_clip_v2\",\"referenceImages\":\"all\"}", "{step:6}");
        ReviewTask pendingTask = new ReviewTask(UUID.randomUUID(), runId, checkpointStep.getId(),
                "approval", "{\"keyframe\":\"old\"}", now, now.plusSeconds(3600), null);

        WorkflowStep storyboardDef = configured(4, "generate_storyboard", "{\"profile\":\"product_ad_storyboard_v1\"}",
                null);
        WorkflowStep keyframeDef = configured(5, "generate_keyframe", keyframeStep.getConfig(), "{step:4}");
        List<String> guardedStepTypes = new ArrayList<>();
        RunStartGuard costGuard = (owner, wfId, defs, ctx) -> defs.forEach(d -> guardedStepTypes.add(d.type()));

        when(media.isEnabled()).thenReturn(true);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(run));
        when(runSteps.findByRunIdOrderByStepOrderAsc(runId)).thenReturn(List.of(keyframeStep, checkpointStep, clip1Step));
        when(reviewTasks.findByRunStepId(checkpointStep.getId())).thenReturn(Optional.of(pendingTask));
        when(workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.MC_ID))
                .thenReturn(List.of(storyboardDef, keyframeDef));
        when(media.boundRunInputs(runId)).thenReturn(List.of(
                new MediaAssetInput(UUID.randomUUID(), new MediaJobInput("image_1", "media", "runs/" + runId + "/inputs/image_1")),
                new MediaAssetInput(UUID.randomUUID(), new MediaJobInput("mc", "media", "runs/" + runId + "/inputs/mc")),
                new MediaAssetInput(UUID.randomUUID(), new MediaJobInput("background", "media", "runs/" + runId + "/inputs/background"))));
        MediaJobInput newBgObject = new MediaJobInput("background", "media", "runs/" + runId + "/inputs/background-v2");
        when(media.putRunInputImageObject(eq(runId), eq("background"), eq(2), any())).thenReturn(newBgObject);
        when(media.replaceRunInputBinding(eq(runId), eq(ownerId), eq(newBgObject), eq(4L)))
                .thenReturn(new MediaAssetInput(UUID.randomUUID(), newBgObject));
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runSteps.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductVideoService service = new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs,
                reviewTasks, stubPresetCatalog(), media, registry, events, runUpdates,
                transactions, new RunStartGuards(List.of(new WorkflowReadinessGuard(), costGuard)), Tracer.NOOP,
                true, true, true);

        service.regenerateKeyframe(runId, ownerId, "bg-warm-wood", "Đổi sang ánh nắng ấm", true);

        assertThat(guardedStepTypes).containsExactly("generate_keyframe");
        verify(media).replaceRunInputBinding(runId, ownerId, newBgObject, 4L);
        verify(reviewTasks).delete(pendingTask);
        verify(reviewTasks).flush();
        assertThat(keyframeStep.getStatus()).isEqualTo(RunStepStatus.PENDING);
        assertThat(keyframeStep.getAttempt()).isEqualTo((short) 2);
        assertThat(keyframeStep.getConfig())
                .contains("\"scenePreset\":\"bg-warm-wood\"")
                .contains("\"sceneNote\":\"Đổi sang ánh nắng ấm\"");
        assertThat(keyframeStep.getInput())
                .contains("runs/" + runId + "/inputs/image_1")
                .contains("runs/" + runId + "/inputs/mc")
                .contains("runs/" + runId + "/inputs/background-v2")
                .doesNotContain("runs/" + runId + "/inputs/background\"");
        assertThat(clip1Step.getInput())
                .contains("runs/" + runId + "/inputs/background-v2")
                .doesNotContain("runs/" + runId + "/inputs/background\"");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        verify(events).publishEvent(any(WorkflowRunStartedEvent.class));
        verify(runUpdates).touched(runId);
    }

    @Test
    void refusesRegenerateKeyframeWhenNotPausedAtKeyframeCheckpoint() {
        UUID ownerId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        WorkflowRun runningRun = new WorkflowRun(runId, ProductVideoWorkflow.MC_ID, ownerId, "{}", Instant.now());
        when(media.isEnabled()).thenReturn(true);
        when(runs.findByIdAndOwnerId(runId, ownerId)).thenReturn(Optional.of(runningRun));

        ProductVideoService service = new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs,
                reviewTasks, new ProductVideoPresetCatalog(true, workflowSteps), media, registry, events, runUpdates,
                transactions, new RunStartGuards(List.of()), Tracer.NOOP, true, true, true);

        assertThatThrownBy(() -> service.regenerateKeyframe(runId, ownerId, null, "Thử lại", false))
                .isInstanceOf(ConflictException.class);
        verify(reviewTasks, never()).delete(any());
    }

    @Test
    void redoesSelectedSceneReusingCompletedClipsAndChargingOnlySubset() {
        UUID ownerId = UUID.randomUUID();
        UUID originalRunId = UUID.randomUUID();
        Instant now = Instant.now();
        WorkflowRun original = new WorkflowRun(originalRunId, ProductVideoWorkflow.MC_ID, ownerId,
                "{\"productName\":\"Đồng hồ\",\"description\":\"Chống nước\"}", now);
        original.markCompleted(now);

        RunStep s3 = new RunStep(UUID.randomUUID(), originalRunId, (short) 3, "generate_speech", null,
                "{\"profile\":\"vi_voice_v1\"}", null);
        s3.markRunning(now);
        s3.markCompleted(null, "{}", now,
                "[{\"name\":\"speech.wav\",\"bucket\":\"media\",\"objectKey\":\"runs/" + originalRunId
                        + "/steps/3/attempts/1/speech.wav\",\"mime\":\"audio/wav\",\"bytes\":1000}]");
        RunStep s7 = new RunStep(UUID.randomUUID(), originalRunId, (short) 7, "generate_video", null,
                "{\"profile\":\"vertical_ad_clip_v2\",\"referenceImages\":\"all\"}", null);
        s7.markRunning(now);
        s7.markCompleted(null, "{}", now);
        RunStep s8 = new RunStep(UUID.randomUUID(), originalRunId, (short) 8, "generate_video", null,
                "{\"profile\":\"vertical_ad_clip_v2\",\"referenceImages\":\"all\"}", null);
        s8.markRunning(now);
        s8.markCompleted(null, "{}", now);
        RunStep s9 = new RunStep(UUID.randomUUID(), originalRunId, (short) 9, "lipsync_video", null,
                "{\"outputInputs\":[{\"stepOrder\":7,\"outputName\":\"clip.mp4\",\"inputKey\":\"video\"}]}", null);
        s9.markRunning(now);
        s9.markCompleted(null, "{}", now);
        RunStep s10 = new RunStep(UUID.randomUUID(), originalRunId, (short) 10, "render_video", null,
                "{\"profile\":\"vertical_ad_v2\"}", null);
        s10.markRunning(now);
        s10.markCompleted(null, "{}", now);

        Workflow workflow = new Workflow(ProductVideoWorkflow.MC_ID, Workflow.SYSTEM_OWNER_ID, "Product Video MC", true);
        List<WorkflowStep> defs = List.of(
                configured(3, "generate_speech", s3.getConfig(), null),
                configured(7, "generate_video", s7.getConfig(), null),
                configured(8, "generate_video", s8.getConfig(), null),
                configured(9, "lipsync_video", s9.getConfig(), null),
                configured(10, "render_video", s10.getConfig(), null));

        List<String> chargedTypes = new ArrayList<>();
        RunStartGuard costGuard = (o, wfId, stepDefs, ctx) -> stepDefs.forEach(d -> chargedTypes.add(d.type()));

        MediaAssetInput boundImg = new MediaAssetInput(UUID.randomUUID(),
                new MediaJobInput("image_1", "media", "runs/" + originalRunId + "/inputs/image_1"));
        MediaAssetInput copiedImg = new MediaAssetInput(boundImg.assetId(),
                new MediaJobInput("image_1", "media", "runs/new/inputs/image_1"));
        MediaOutput copiedSpeech = new MediaOutput("speech.wav", "media",
                "runs/new/steps/3/attempts/1/speech.wav", "audio/wav", 1000L, null);

        when(media.isEnabled()).thenReturn(true);
        when(runs.findByIdAndOwnerId(originalRunId, ownerId)).thenReturn(Optional.of(original));
        when(runSteps.findByRunIdOrderByStepOrderAsc(originalRunId)).thenReturn(List.of(s3, s7, s8, s9, s10));
        when(workflows.findVisibleTo(ProductVideoWorkflow.MC_ID, ownerId)).thenReturn(Optional.of(workflow));
        when(workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.MC_ID)).thenReturn(defs);
        when(media.boundRunInputs(originalRunId)).thenReturn(List.of(boundImg));
        when(media.copyRunInputs(any(), eq(List.of(boundImg)))).thenReturn(List.of(copiedImg));
        when(media.copyStepOutputs(eq(originalRunId), any(), eq(3), any())).thenReturn(List.of(copiedSpeech));
        when(inputs.findById(originalRunId)).thenReturn(Optional.empty());
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runSteps.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductVideoService service = new ProductVideoService(workflows, workflowSteps, runs, runSteps, inputs,
                reviewTasks, stubPresetCatalog(), media, registry, events, runUpdates, transactions,
                new RunStartGuards(List.of(new WorkflowReadinessGuard(), costGuard)), Tracer.NOOP,
                true, true, true);

        var created = service.redoSteps(originalRunId, ownerId, List.of(8), "Cận cảnh chậm hơn", true);

        assertThat(created.id()).isNotEqualTo(originalRunId);
        assertThat(chargedTypes).containsExactly("generate_video", "render_video");
        ArgumentCaptor<List<RunStep>> savedSteps = ArgumentCaptor.forClass(List.class);
        verify(runSteps).saveAll(savedSteps.capture());
        assertThat(savedSteps.getValue()).filteredOn(s -> s.getStepOrder() == 3 || s.getStepOrder() == 7 || s.getStepOrder() == 9)
                .allMatch(s -> s.getStatus() == RunStepStatus.COMPLETED);
        assertThat(savedSteps.getValue()).filteredOn(s -> s.getStepOrder() == 8 || s.getStepOrder() == 10)
                .allMatch(s -> s.getStatus() == RunStepStatus.PENDING);
        assertThat(savedSteps.getValue()).filteredOn(s -> s.getStepOrder() == 8)
                .singleElement()
                .extracting(RunStep::getConfig)
                .asString()
                .contains("\"promptSuffix\":\"Cận cảnh chậm hơn\"");
        verify(media).registerOutputs(eq(ownerId), eq(created.id()), any(), eq(3), eq((short) 1),
                eq(List.of(copiedSpeech)));
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

    private ProductVideoPresetCatalog stubPresetCatalog() {
        return new ProductVideoPresetCatalog(true, workflowSteps) {
            @Override
            public byte[] loadPresetImage(String category, String id) {
                return new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00 };
            }
        };
    }

    private static ProductVideoDraft draft() {
        return new ProductVideoDraft("Bình giữ nhiệt", "Giữ đồ uống tiện lợi", "Mua ngay", "299.000đ", null,
                "9:16");
    }
}
