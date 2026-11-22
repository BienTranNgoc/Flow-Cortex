package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The checks a run start applies before it creates anything, in the order it applies them, split where the
 * request-key lookup sits: {@link #resolve} before it and {@link #checkSteps} after it. A batch applies both
 * once at creation, so what a run start refuses a batch refuses too.
 */
@Component
class RunStartPreflight {

    private final WorkflowRepository workflowRepository;
    private final ServedDefinitions servedDefinitions;
    private final StepExecutorRegistry registry;
    private final MediaJobs mediaJobs;
    private final RunStartGuards runStartGuards;
    private final boolean workflowEnabled;

    @Autowired
    RunStartPreflight(WorkflowRepository workflowRepository, ServedDefinitions servedDefinitions,
            StepExecutorRegistry registry, MediaJobs mediaJobs, RunStartGuards runStartGuards,
            @Value("${flowcortex.workflow.enabled:true}") boolean workflowEnabled) {
        this.workflowRepository = workflowRepository;
        this.servedDefinitions = servedDefinitions;
        this.registry = registry;
        this.mediaJobs = mediaJobs;
        this.runStartGuards = runStartGuards;
        this.workflowEnabled = workflowEnabled;
    }

    /**
     * The workflow the caller may run.
     * @throws LocalizedStatusException 503 when workflow execution is switched off, 400 for the Product Video workflow
     * @throws NotFoundException when the workflow is unknown or not visible to {@code ownerId}
     */
    Workflow resolve(UUID ownerId, UUID workflowId) {
        // Kill switch — checked BEFORE anything is persisted: off means no
        // run is created at all, not a run that is immediately refused.
        if (!workflowEnabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.disabled"));
        }
        if (ProductVideoWorkflow.ID.equals(workflowId)) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.startElsewhere"));
        }
        return workflowRepository.findVisibleTo(workflowId, ownerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
    }

    /**
     * The steps a run of {@code workflow} would execute, once the media and dub availability and every start guard
     * have passed.
     * @throws LocalizedStatusException 503 when a remote step needs the media engine while it is off
     * @throws DubDisabledException when a step's type is not available
     */
    List<WorkflowStep> checkSteps(UUID ownerId, Workflow workflow, boolean acknowledgeCost) {
        List<WorkflowStep> stepDefs = servedDefinitions.stepsFor(workflow, ownerId);
        // Refused up front: a run whose remote step would only fail media_disabled later.
        if (stepDefs.stream().anyMatch(step -> registry.isRemote(step.getType())) && !mediaJobs.isEnabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled"));
        }
        if (stepDefs.stream().anyMatch(step -> !registry.isAvailable(step.getType()))) {
            throw new DubDisabledException();
        }
        runStartGuards.check(ownerId, workflow.getId(), stepDefs, new StartContext(acknowledgeCost));
        return stepDefs;
    }
}
