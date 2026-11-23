package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.UploadPurpose;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.api.ProductVideoRuns;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunView;
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
            MediaJobs mediaJobs, StepExecutorRegistry registry, ApplicationEventPublisher events,
            RunUpdates runUpdates, TransactionTemplate transactions, RunStartGuards runStartGuards,
            ObjectProvider<Tracer> tracer,
            @Value("${flowcortex.product-video.enabled:false}") boolean enabled,
            @Value("${flowcortex.workflow.enabled:true}") boolean workflowEnabled,
            @Value("${flowcortex.product-video.mc-enabled:false}") boolean mcEnabled) {
        this(workflows, workflowSteps, runs, runSteps, productInputs, mediaJobs, registry, events, runUpdates,
                transactions, runStartGuards, tracer.getIfAvailable(() -> Tracer.NOOP), enabled, workflowEnabled,
                mcEnabled);
    }

    ProductVideoService(WorkflowRepository workflows, WorkflowStepRepository workflowSteps,
            WorkflowRunRepository runs, RunStepRepository runSteps, ProductVideoInputRepository productInputs,
            MediaJobs mediaJobs, StepExecutorRegistry registry, ApplicationEventPublisher events,
            RunUpdates runUpdates, TransactionTemplate transactions, RunStartGuards runStartGuards, Tracer tracer,
            boolean enabled, boolean workflowEnabled) {
        this(workflows, workflowSteps, runs, runSteps, productInputs, mediaJobs, registry, events, runUpdates,
                transactions, runStartGuards, tracer, enabled, workflowEnabled, false);
    }

    ProductVideoService(WorkflowRepository workflows, WorkflowStepRepository workflowSteps,
            WorkflowRunRepository runs, RunStepRepository runSteps, ProductVideoInputRepository productInputs,
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
        if ("mc-v1".equals(draft.templateId()) && !mcEnabled) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.mcDisabled"));
        }
        Workflow workflow = workflows.findVisibleTo(ProductVideoWorkflow.ID, ownerId)
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
        List<WorkflowStep> definitions = workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.ID);
        if ("mc-v1".equals(draft.templateId()) && !hasMcSteps(definitions)) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.productVideo.mcNotConfigured"));
        }
        runStartGuards.check(ownerId, workflow.getId(), definitions, new StartContext(acknowledgeCost));
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
        List<MediaAssetInput> copies = mediaJobs.copyRunInputs(runId, assets);
        String assetJson = json(copies.stream().map(MediaAssetInput::input).toList());
        String productAssetJson = json(copies.stream()
                .map(MediaAssetInput::input)
                .filter(input -> input.key() != null && input.key().startsWith("image_"))
                .toList());
        Instant now = Instant.now();
        String traceId = CurrentTrace.idFor(tracer);

        return transactions.execute(status -> {
            WorkflowRun run = runs.save(new WorkflowRun(runId, ProductVideoWorkflow.ID, ownerId, productJson, null,
                    now, requestKey).withTraceId(traceId));
            productInputs.save(new ProductVideoInput(run.getId(), draft.productName(), draft.description(),
                    draft.cta(), draft.price(), draft.promotion(), draft.aspectRatio(),
                    draft.templateId(), 1, draft.voiceGender(), draft.mcPresetId(),
                    draft.backgroundPresetId(), draft.sceneNote(), now));
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

    private static boolean hasMcSteps(List<WorkflowStep> definitions) {
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
        if ("generate_speech".equals(stepType)
                && draft.voiceGender() != null && !"male".equals(draft.voiceGender())) {
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
