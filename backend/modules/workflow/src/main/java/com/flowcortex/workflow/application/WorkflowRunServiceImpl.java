package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implementation of {@link WorkflowRunService}. {@link #startRun} is tx#1 of
 * the async after-commit execution model: creates the {@code WorkflowRun} +
 * its {@code pending} {@code RunStep}s,
 * commits, and publishes {@link WorkflowRunStartedEvent} — {@link
 * WorkflowRunOrchestrator} picks that event up AFTER this transaction
 * commits and drives the actual execution, so this method returns before any
 * step has run.
 */
@Service
class WorkflowRunServiceImpl implements WorkflowRunService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final ApplicationEventPublisher events;
    private final boolean workflowEnabled;

    WorkflowRunServiceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            ApplicationEventPublisher events,
            @Value("${flowcortex.workflow.enabled:true}") boolean workflowEnabled) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.events = events;
        this.workflowEnabled = workflowEnabled;
    }

    @Override
    @Transactional
    public RunView startRun(UUID currentUserId, UUID workflowId, String input) {
        // Kill switch — checked BEFORE anything is persisted: off means no
        // run is created at all, not a run that is immediately refused.
        if (!workflowEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Workflow execution is currently disabled");
        }

        Workflow workflow = workflowRepository.findVisibleTo(workflowId, currentUserId)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + workflowId));
        List<WorkflowStep> stepDefs = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId());

        Instant now = Instant.now();
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), workflow.getId(), currentUserId, input, now);
        run = workflowRunRepository.save(run);

        List<RunStep> runSteps = new ArrayList<>();
        for (WorkflowStep stepDef : stepDefs) {
            runSteps.add(new RunStep(UUID.randomUUID(), run.getId(), stepDef.getStepOrder(), stepDef.getType()));
        }
        runSteps = runStepRepository.saveAll(runSteps);

        // Published within this transaction; WorkflowRunOrchestrator's
        // @ApplicationModuleListener only fires AFTER this transaction
        // commits — this method returns first.
        events.publishEvent(new WorkflowRunStartedEvent(run.getId()));

        return WorkflowMapper.toRunView(run, runSteps);
    }

    @Override
    @Transactional(readOnly = true)
    public RunView getRun(UUID currentUserId, UUID runId) {
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, currentUserId)
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        List<RunStep> steps = runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        return WorkflowMapper.toRunView(run, steps);
    }
}
