package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.api.ProductVideoRuns;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.domain.ProductVideoInput;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.ReviewTaskStatus;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Starts the seeded Product Video workflow with an immutable product/asset snapshot. */
@Service
public class ProductVideoService implements ProductVideoRuns {

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository workflowSteps;
    private final WorkflowRunRepository runs;
    private final RunStepRepository runSteps;
    private final ProductVideoInputRepository productInputs;
    private final ReviewTaskRepository reviewTasks;
    private final ProductVideoPresetCatalog presetCatalog;
    private final MediaJobs mediaJobs;
    private final StepExecutorRegistry registry;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final RunStartGuards runStartGuards;
    private final boolean enabled;
    private final boolean workflowEnabled;
    private final boolean mcEnabled;
    private final RunUpdates runUpdates;
    private final Tracer tracer;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    ProductVideoService(WorkflowRepository workflows, WorkflowStepRepository workflowSteps,
            WorkflowRunRepository runs, RunStepRepository runSteps, ProductVideoInputRepository productInputs,
            ReviewTaskRepository reviewTasks, ProductVideoPresetCatalog presetCatalog,
            MediaJobs mediaJobs, StepExecutorRegistry registry, ApplicationEventPublisher events,
            RunUpdates runUpdates, TransactionTemplate transactions, RunStartGuards runStartGuards,
            ObjectProvider<Tracer> tracer,
            @Value("${flowcortex.product-video.enabled:false}") boolean enabled,
            @Value("${flowcortex.workflow.enabled:true}") boolean workflowEnabled,
            @Value("${flowcortex.product-video.mc-enabled:false}") boolean mcEnabled) {
        this(workflows, workflowSteps, runs, runSteps, productInputs, reviewTasks, presetCatalog, mediaJobs,
                registry, events, runUpdates, transactions, runStartGuards,
                tracer.getIfAvailable(() -> Tracer.NOOP), enabled, workflowEnabled, mcEnabled);
    }

    ProductVideoService(WorkflowRepository workflows, WorkflowStepRepository workflowSteps,
            WorkflowRunRepository runs, RunStepRepository runSteps, ProductVideoInputRepository productInputs,
            MediaJobs mediaJobs, StepExecutorRegistry registry, ApplicationEventPublisher events,
            RunUpdates runUpdates, TransactionTemplate transactions, RunStartGuards runStartGuards, Tracer tracer,
            boolean enabled, boolean workflowEnabled) {
        this(workflows, workflowSteps, runs, runSteps, productInputs, null,
                new ProductVideoPresetCatalog(false, workflowSteps), mediaJobs, registry, events, runUpdates,
                transactions, runStartGuards, tracer, enabled, workflowEnabled, false);
    }

    ProductVideoService(WorkflowRepository workflows, WorkflowStepRepository workflowSteps,
            WorkflowRunRepository runs, RunStepRepository runSteps, ProductVideoInputRepository productInputs,
            MediaJobs mediaJobs, StepExecutorRegistry registry, ApplicationEventPublisher events,
            RunUpdates runUpdates, TransactionTemplate transactions, RunStartGuards runStartGuards, Tracer tracer,
            boolean enabled, boolean workflowEnabled, boolean mcEnabled) {
        this(workflows, workflowSteps, runs, runSteps, productInputs, null,
                new ProductVideoPresetCatalog(mcEnabled, workflowSteps), mediaJobs, registry, events, runUpdates,
                transactions, runStartGuards, tracer, enabled, workflowEnabled, mcEnabled);
    }

    ProductVideoService(WorkflowRepository workflows, WorkflowStepRepository workflowSteps,
            WorkflowRunRepository runs, RunStepRepository runSteps, ProductVideoInputRepository productInputs,
            ReviewTaskRepository reviewTasks, ProductVideoPresetCatalog presetCatalog,
            MediaJobs mediaJobs, StepExecutorRegistry registry, ApplicationEventPublisher events,
            RunUpdates runUpdates, TransactionTemplate transactions, RunStartGuards runStartGuards, Tracer tracer,
            boolean enabled, boolean workflowEnabled, boolean mcEnabled) {
        this.tracer = tracer;
        this.runUpdates = runUpdates;
        this.workflows = workflows;
        this.workflowSteps = workflowSteps;
        this.runs = runs;
        this.runSteps = runSteps;
        this.productInputs = productInputs;
        this.reviewTasks = reviewTasks;
        this.presetCatalog = presetCatalog;
        this.mediaJobs = mediaJobs;
        this.registry = registry;
        this.events = events;
        this.transactions = transactions;
        this.runStartGuards = runStartGuards;
        this.enabled = enabled;
        this.workflowEnabled = workflowEnabled;
        this.mcEnabled = mcEnabled;
    }

    public RunView start(UUID ownerId, ProductVideoDraft draft, List<UUID> uploadIds) {
        return start(ownerId, draft, uploadIds, false);
    }

    public RunView start(UUID ownerId, ProductVideoDraft draft, List<UUID> uploadIds, boolean acknowledgeCost) {
        return startRun(ownerId, draft, uploadIds, null, null, acknowledgeCost, null);
    }

    @Override
    public RunView start(UUID ownerId, ProductVideoDraft draft, List<UUID> uploadIds, boolean acknowledgeCost,
            String requestKey) {
        return start(ownerId, draft, uploadIds, null, null, acknowledgeCost, requestKey);
    }

    public RunView start(UUID ownerId, ProductVideoDraft draft, List<UUID> uploadIds,
            UUID mcUploadId, UUID backgroundUploadId, boolean acknowledgeCost, String requestKey) {
        ProductVideoDraft validated = ProductVideoDraft.of(draft.productName(), draft.description(), draft.cta(),
                draft.price(), draft.promotion(), draft.aspectRatio(), draft.templateId(), draft.voiceGender(),
                draft.mcPresetId(), draft.backgroundPresetId(), draft.sceneNote());
        ProductVideoDraft.requireUploads(uploadIds);
        return startRun(ownerId, validated, uploadIds, mcUploadId, backgroundUploadId, acknowledgeCost, requestKey);
    }

    private RunView startRun(UUID ownerId, ProductVideoDraft draft, List<UUID> uploadIds,
            UUID mcUploadId, UUID backgroundUploadId, boolean acknowledgeCost, String requestKey) {
        if (!enabled || !workflowEnabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.productVideo.disabled"));
        }
        if (!mediaJobs.isEnabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled"));
        }
        boolean isMc = "mc-v1".equals(draft.templateId());
        if (isMc && !mcEnabled) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.mcDisabled"));
        }
        if (isMc && mcUploadId == null && draft.mcPresetId() == null) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.mcRequired"));
        }
        if (draft.mcPresetId() != null && !presetCatalog.isValidMcPreset(draft.mcPresetId())) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.invalidPreset"));
        }
        if (draft.backgroundPresetId() != null && !presetCatalog.isValidBackgroundPreset(draft.backgroundPresetId())) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.invalidPreset"));
        }
        UUID targetWorkflowId = isMc ? ProductVideoWorkflow.MC_ID : ProductVideoWorkflow.ID;
        Workflow workflow = workflows.findVisibleTo(targetWorkflowId, ownerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.productVideo.notInstalled")));
        // A retried start answers with its run before any cost guard or object copy runs again.
        Optional<RunView> started = RunRequestKeys.existing(runs, runSteps, registry, mediaJobs::isDownloadable,
                ownerId, requestKey);
        if (started.isPresent()) {
            return started.get();
        }
        return RunRequestKeys.orWinner(
                () -> startNew(ownerId, workflow, draft, uploadIds, mcUploadId, backgroundUploadId,
                        acknowledgeCost, requestKey),
                runs, runSteps, registry, mediaJobs::isDownloadable, ownerId, requestKey);
    }

    private RunView startNew(UUID ownerId, Workflow workflow, ProductVideoDraft draft, List<UUID> uploadIds,
            UUID mcUploadId, UUID backgroundUploadId, boolean acknowledgeCost, String requestKey) {
        UUID targetWorkflowId = workflow.getId();
        List<WorkflowStep> definitions = workflowSteps.findByWorkflowIdOrderByStepOrderAsc(targetWorkflowId);
        if ("mc-v1".equals(draft.templateId()) && !hasMcSteps(definitions)) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.productVideo.mcNotConfigured"));
        }
        runStartGuards.check(ownerId, targetWorkflowId, definitions, new StartContext(acknowledgeCost));
        List<MediaAssetInput> assets = new ArrayList<>(
                mediaJobs.resolveImageUploads(UploadPurpose.RUN_INPUT, ownerId, uploadIds));
        if (mcUploadId != null) {
            assets.add(rekeyAsset(mediaJobs.resolveImageUploads(
                    UploadPurpose.RUN_INPUT, ownerId, List.of(mcUploadId)).getFirst(), "mc"));
        }
        if (backgroundUploadId != null) {
            assets.add(rekeyAsset(mediaJobs.resolveImageUploads(
                    UploadPurpose.RUN_INPUT, ownerId, List.of(backgroundUploadId)).getFirst(), "background"));
        }
        String productJson = productJson(draft);
        // Copied before the run's transaction opens, so no database transaction waits on the object store.
        UUID runId = UUID.randomUUID();
        List<MediaAssetInput> copies = new ArrayList<>(mediaJobs.copyRunInputs(runId, assets));
        if (mcUploadId == null && draft.mcPresetId() != null) {
            byte[] mcBytes = presetCatalog.loadPresetImage("mc", draft.mcPresetId());
            copies.add(mediaJobs.storeRunInputImage(runId, ownerId, "mc", mcBytes));
        }
        if (backgroundUploadId == null && draft.backgroundPresetId() != null) {
            byte[] bgBytes = presetCatalog.loadPresetImage("backgrounds", draft.backgroundPresetId());
            copies.add(mediaJobs.storeRunInputImage(runId, ownerId, "background", bgBytes));
        }
        String assetJson = json(copies.stream().map(MediaAssetInput::input).toList());
        String productAssetJson = json(copies.stream()
                .map(MediaAssetInput::input)
                .filter(input -> input.key() != null && input.key().startsWith("image_"))
                .toList());
        Instant now = Instant.now();
        String traceId = CurrentTrace.idFor(tracer);

        return transactions.execute(status -> {
            WorkflowRun run = runs.save(new WorkflowRun(runId, targetWorkflowId, ownerId, productJson, null,
                    now, requestKey).withTraceId(traceId));
            productInputs.save(new ProductVideoInput(run.getId(), draft.productName(), draft.description(),
                    draft.cta(), draft.price(), draft.promotion(), draft.aspectRatio(),
                    draft.templateId(), 1, draft.voiceGender() != null ? draft.voiceGender() : "male",
                    draft.mcPresetId(), draft.backgroundPresetId(), draft.sceneNote(), now));
            mediaJobs.bindRunInputs(run.getId(), copies);
            List<RunStep> steps = new ArrayList<>();
            for (WorkflowStep definition : definitions) {
                String storedInput = null;
                if (registry.snapshotsRunInputs(definition.getType())
                        || namesReferenceImage(definition.getConfig())) {
                    storedInput = "render_video".equals(definition.getType()) ? productAssetJson : assetJson;
                }
                String stepConfig = enrichStepConfig(definition.getType(), definition.getConfig(), draft);
                steps.add(new RunStep(UUID.randomUUID(), run.getId(), definition.getStepOrder(),
                        definition.getType(), storedInput, stepConfig, definition.getInputRef()));
            }
            steps = runSteps.saveAll(steps);
            runUpdates.touched(run.getId());
            events.publishEvent(new WorkflowRunStartedEvent(run.getId()));
            return WorkflowMapper.toRunView(run, steps, List.of(), registry::isRemote, mediaJobs::isDownloadable);
        });
    }

    public RunView regenerateKeyframe(UUID runId, UUID ownerId, String rawBackgroundPresetId,
            String rawSceneNote, boolean acknowledgeCost) {
        if (!enabled || !workflowEnabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.productVideo.disabled"));
        }
        if (!mediaJobs.isEnabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled"));
        }
        String backgroundPresetId = normalizeOptional(rawBackgroundPresetId);
        String sceneNote = normalizeOptional(rawSceneNote);
        if (backgroundPresetId != null && !presetCatalog.isValidBackgroundPreset(backgroundPresetId)) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.invalidPreset"));
        }

        WorkflowRun initialRun = runs.findByIdAndOwnerId(runId, ownerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
        KeyframeReviewContext ctx = requireKeyframeReviewContext(initialRun);

        List<WorkflowStep> definitions = workflowSteps.findByWorkflowIdOrderByStepOrderAsc(initialRun.getWorkflowId());
        WorkflowStep keyframeDef = definitions.stream()
                .filter(d -> d.getStepOrder() == ctx.keyframeStep().getStepOrder())
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        new LocalizedMessage("workflow.productVideo.keyframeReviewNotPending")));
        runStartGuards.checkSingleStep(ownerId, initialRun.getWorkflowId(), definitions, keyframeDef,
                new StartContext(acknowledgeCost));

        int nextAttempt = ctx.keyframeStep().getAttempt() + 1;
        UUID initialTaskId = ctx.pendingTask().getId();
        MediaJobInput uploadedBackground = null;
        long uploadedBackgroundBytes = 0L;
        if (backgroundPresetId != null) {
            byte[] bgBytes = presetCatalog.loadPresetImage("backgrounds", backgroundPresetId);
            uploadedBackground = mediaJobs.putRunInputImageObject(runId, "background", nextAttempt, bgBytes);
            uploadedBackgroundBytes = bgBytes.length;
        }
        MediaJobInput storedBackground = uploadedBackground;
        long storedBackgroundBytes = uploadedBackgroundBytes;

        return transactions.execute(status -> {
            runs.lockById(runId);
            WorkflowRun run = runs.findByIdAndOwnerId(runId, ownerId)
                    .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
            KeyframeReviewContext locked = requireKeyframeReviewContext(run);
            if (!locked.pendingTask().getId().equals(initialTaskId)) {
                throw new ConflictException(new LocalizedMessage("workflow.productVideo.keyframeReviewNotPending"));
            }

            if (storedBackground != null) {
                mediaJobs.replaceRunInputBinding(runId, ownerId, storedBackground, storedBackgroundBytes);
            }

            reviewTasks.delete(locked.pendingTask());
            reviewTasks.flush();

            String updatedConfig = mergeKeyframePayloadDefaults(locked.keyframeStep().getConfig(),
                    backgroundPresetId, sceneNote);
            List<MediaJobInput> restoredInputs = restoreRunInputs(runId, storedBackground);
            String updatedInput = json(restoredInputs);
            locked.keyframeStep().resetForRegeneration(updatedConfig, updatedInput);
            run.resume();

            runSteps.save(locked.keyframeStep());
            runs.save(run);
            runUpdates.touched(runId);
            events.publishEvent(new WorkflowRunStartedEvent(runId));
            return WorkflowMapper.toRunView(run, locked.allSteps(), List.of(),
                    registry::isRemote, mediaJobs::isDownloadable);
        });
    }

    private record KeyframeReviewContext(List<RunStep> allSteps, RunStep keyframeStep, RunStep checkpointStep,
            ReviewTask pendingTask) {
    }

    private KeyframeReviewContext requireKeyframeReviewContext(WorkflowRun run) {
        if (run.getStatus() != RunStatus.WAITING_APPROVAL || reviewTasks == null) {
            throw new ConflictException(new LocalizedMessage("workflow.productVideo.keyframeReviewNotPending"));
        }
        List<RunStep> steps = runSteps.findByRunIdOrderByStepOrderAsc(run.getId());
        RunStep checkpointStep = steps.stream()
                .filter(s -> HumanSteps.APPROVAL_CHECKPOINT.equals(s.getType())
                        && s.getStatus() == RunStepStatus.PENDING
                        && isKeyframeReviewConfig(s.getConfig()))
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        new LocalizedMessage("workflow.productVideo.keyframeReviewNotPending")));
        RunStep keyframeStep = steps.stream()
                .filter(s -> s.getStepOrder() == checkpointStep.getStepOrder() - 1
                        && "generate_keyframe".equals(s.getType())
                        && s.getStatus() == RunStepStatus.COMPLETED)
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        new LocalizedMessage("workflow.productVideo.keyframeReviewNotPending")));
        ReviewTask pendingTask = reviewTasks.findByRunStepId(checkpointStep.getId())
                .filter(t -> t.getStatus() == ReviewTaskStatus.PENDING)
                .orElseThrow(() -> new ConflictException(
                        new LocalizedMessage("workflow.productVideo.keyframeReviewNotPending")));
        return new KeyframeReviewContext(steps, keyframeStep, checkpointStep, pendingTask);
    }

    private boolean isKeyframeReviewConfig(String config) {
        if (config == null || config.isBlank()) {
            return false;
        }
        try {
            JsonNode kind = json.readTree(config).get("kind");
            return kind != null && "keyframe_review".equals(kind.asText());
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private String mergeKeyframePayloadDefaults(String rawConfig, String backgroundPresetId, String sceneNote) {
        if (backgroundPresetId == null && sceneNote == null) {
            return rawConfig;
        }
        try {
            Map<String, Object> config = rawConfig == null || rawConfig.isBlank()
                    ? new LinkedHashMap<>()
                    : json.readValue(rawConfig, new TypeReference<>() { });
            Map<String, Object> defaults = new LinkedHashMap<>();
            Object existing = config.get("payloadDefaults");
            if (existing instanceof Map<?, ?> map) {
                map.forEach((k, v) -> defaults.put(String.valueOf(k), v));
            }
            if (backgroundPresetId != null) {
                defaults.put("scenePreset", backgroundPresetId);
            }
            if (sceneNote != null) {
                defaults.put("sceneNote", sceneNote);
            }
            config.put("payloadDefaults", defaults);
            return json.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Product Video step config is not valid JSON", e);
        }
    }

    private List<MediaJobInput> restoreRunInputs(UUID runId, MediaJobInput storedBackground) {
        List<MediaJobInput> entries = new ArrayList<>();
        for (MediaAssetInput bound : mediaJobs.boundRunInputs(runId)) {
            MediaJobInput input = bound.input();
            if (storedBackground != null && storedBackground.key().equals(input.key())) {
                continue;
            }
            entries.add(input);
        }
        if (storedBackground != null) {
            entries.add(storedBackground);
        }
        return entries;
    }

    private static String normalizeOptional(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static boolean hasMcSteps(List<WorkflowStep> definitions) {
        boolean hasKeyframe = false;
        boolean hasLipsync = false;
        for (WorkflowStep step : definitions) {
            if ("generate_keyframe".equals(step.getType())) {
                hasKeyframe = true;
            } else if ("lipsync_video".equals(step.getType())) {
                hasLipsync = true;
            }
        }
        return hasKeyframe && hasLipsync;
    }

    private String enrichStepConfig(String stepType, String rawConfig, ProductVideoDraft draft) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        if ("generate_speech".equals(stepType) && draft.voiceGender() != null) {
            defaults.put("voiceGender", draft.voiceGender());
        } else if ("generate_storyboard".equals(stepType)) {
            if (draft.templateId() != null && !"showroom-v1".equals(draft.templateId())) {
                defaults.put("templateId", draft.templateId());
            }
            if (draft.backgroundPresetId() != null) {
                defaults.put("scenePreset", draft.backgroundPresetId());
            }
            if (draft.sceneNote() != null) {
                defaults.put("sceneNote", draft.sceneNote());
            }
        } else if ("generate_keyframe".equals(stepType)) {
            if (draft.backgroundPresetId() != null) {
                defaults.put("scenePreset", draft.backgroundPresetId());
            }
            if (draft.sceneNote() != null) {
                defaults.put("sceneNote", draft.sceneNote());
            }
        }
        if (defaults.isEmpty()) {
            return rawConfig;
        }
        try {
            Map<String, Object> config = rawConfig == null || rawConfig.isBlank()
                    ? new LinkedHashMap<>()
                    : json.readValue(rawConfig, new com.fasterxml.jackson.core.type.TypeReference<>() { });
            config.put("payloadDefaults", defaults);
            return json.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Product Video step config is not valid JSON", e);
        }
    }

    private static MediaAssetInput rekeyAsset(MediaAssetInput asset, String key) {
        MediaJobInput input = asset.input();
        return new MediaAssetInput(asset.assetId(), new MediaJobInput(key, input.bucket(), input.objectKey()));
    }

    private String productJson(ProductVideoDraft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productName", draft.productName());
        payload.put("description", draft.description());
        if (draft.cta() != null) {
            payload.put("cta", draft.cta());
        }
        if (draft.price() != null) {
            payload.put("price", draft.price());
        }
        if (draft.promotion() != null) {
            payload.put("promotion", draft.promotion());
        }
        if (draft.templateId() != null && !"showroom-v1".equals(draft.templateId())) {
            payload.put("templateId", draft.templateId());
        }
        if (draft.backgroundPresetId() != null) {
            payload.put("scenePreset", draft.backgroundPresetId());
        }
        if (draft.sceneNote() != null) {
            payload.put("sceneNote", draft.sceneNote());
        }
        return json(payload);
    }

    /** A step that sends one of the run's product images to its provider reads it from its stored input. */
    private boolean namesReferenceImage(String config) {
        if (config == null || config.isBlank()) {
            return false;
        }
        try {
            var tree = json.readTree(config);
            return tree.has("referenceImage") || tree.has("referenceImages");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Product Video step config is not valid JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Product Video input could not be serialized", e);
        }
    }
}
