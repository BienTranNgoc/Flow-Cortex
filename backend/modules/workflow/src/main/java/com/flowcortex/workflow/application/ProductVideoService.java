package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ProductVideoDraft;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Starts the seeded Product Video workflow with an immutable product/asset snapshot. */
@Service
public class ProductVideoService {

    private final WorkflowRepository workflows;
    private final WorkflowStepRepository workflowSteps;
    private final WorkflowRunRepository runs;
    private final RunStepRepository runSteps;
    private final ProductVideoInputRepository productInputs;
    private final MediaJobs mediaJobs;
    private final StepExecutorRegistry registry;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final boolean workflowEnabled;
    private final ObjectMapper json = new ObjectMapper();

    ProductVideoService(WorkflowRepository workflows, WorkflowStepRepository workflowSteps,
            WorkflowRunRepository runs, RunStepRepository runSteps, ProductVideoInputRepository productInputs,
            MediaJobs mediaJobs, StepExecutorRegistry registry, ApplicationEventPublisher events,
            TransactionTemplate transactions,
            @Value("${flowcortex.product-video.enabled:false}") boolean enabled,
            @Value("${flowcortex.workflow.enabled:true}") boolean workflowEnabled) {
        this.workflows = workflows;
        this.workflowSteps = workflowSteps;
        this.runs = runs;
        this.runSteps = runSteps;
        this.productInputs = productInputs;
        this.mediaJobs = mediaJobs;
        this.registry = registry;
        this.events = events;
        this.transactions = transactions;
        this.enabled = enabled;
        this.workflowEnabled = workflowEnabled;
    }

    public RunView start(UUID ownerId, ProductVideoDraft draft, List<UUID> uploadIds) {
        if (!enabled || !workflowEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Product Video is currently disabled");
        }
        if (!mediaJobs.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media engine is currently disabled");
        }
        Workflow workflow = workflows.findVisibleTo(ProductVideoWorkflow.ID, ownerId)
                .orElseThrow(() -> new NotFoundException("Product Video workflow is not installed"));
        List<WorkflowStep> definitions = workflowSteps.findByWorkflowIdOrderByStepOrderAsc(ProductVideoWorkflow.ID);
        List<MediaAssetInput> assets = mediaJobs.resolveImageUploads(ownerId, uploadIds);
        String productJson = productJson(draft);
        Instant now = Instant.now();

        return transactions.execute(status -> {
            WorkflowRun run = runs.save(new WorkflowRun(UUID.randomUUID(), ProductVideoWorkflow.ID, ownerId, productJson, now));
            productInputs.save(new ProductVideoInput(run.getId(), draft.productName(), draft.description(),
                    draft.cta(), draft.price(), draft.promotion(), draft.aspectRatio(), now));
            List<MediaAssetInput> bound = mediaJobs.bindRunInputs(run.getId(), assets);
            String assetJson = json(bound.stream().map(MediaAssetInput::input).toList());
            List<RunStep> steps = new ArrayList<>();
            for (WorkflowStep definition : definitions) {
                String storedInput = registry.usesStoredInput(definition.getType())
                        || namesReferenceImage(definition.getConfig()) ? assetJson : null;
                steps.add(new RunStep(UUID.randomUUID(), run.getId(), definition.getStepOrder(),
                        definition.getType(), storedInput, definition.getConfig(), definition.getInputRef()));
            }
            steps = runSteps.saveAll(steps);
            events.publishEvent(new WorkflowRunStartedEvent(run.getId()));
            return WorkflowMapper.toRunView(run, steps, registry::isRemote);
        });
    }

    private String productJson(ProductVideoDraft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productName", draft.productName());
        payload.put("description", draft.description());
        payload.put("cta", draft.cta());
        if (draft.price() != null) {
            payload.put("price", draft.price());
        }
        if (draft.promotion() != null) {
            payload.put("promotion", draft.promotion());
        }
        return json(payload);
    }

    /** A step that sends one of the run's product images to its provider reads it from its stored input. */
    private boolean namesReferenceImage(String config) {
        if (config == null || config.isBlank()) {
            return false;
        }
        try {
            return json.readTree(config).has("referenceImage");
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
