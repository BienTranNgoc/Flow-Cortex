package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies a workflow another member shared into the caller's own drafts. The copy carries the steps the caller is
 * served (their pin, else the published version) under the name the caller sees; it takes no lock on the source, so
 * it holds what was served at read time while the source's count moves by one atomic update.
 */
@Service
public class WorkflowForkService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final ServedDefinitions servedDefinitions;
    private final StepExecutorRegistry registry;

    WorkflowForkService(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            ServedDefinitions servedDefinitions, StepExecutorRegistry registry) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.servedDefinitions = servedDefinitions;
        this.registry = registry;
    }

    /**
     * @throws NotFoundException the workflow is not visible to the caller
     * @throws UnprocessableEntityException the caller owns the workflow ({@code CANNOT_FORK_OWN})
     * @throws ConflictException the workflow is the fixed Product Video workflow
     * @throws LocalizedStatusException 403: the owner has not allowed forking
     */
    @Transactional
    public WorkflowView fork(UUID callerId, UUID sourceId) {
        Workflow source = workflowRepository.findVisibleTo(sourceId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", sourceId)));
        ForkEligibility.refusal(source.getId(), source.getOwnerId(), source.isAllowFork(), callerId)
                .ifPresent(WorkflowForkService::refuse);

        List<StepDefinition> served = WorkflowCarry.of(servedDefinitions.stepsFor(source, callerId));
        Workflow fork = workflowRepository.save(
                Workflow.forkOf(UUID.randomUUID(), callerId, source.getName(), source.getId()));
        List<WorkflowStep> steps = workflowStepRepository.saveAll(WorkflowCarry.copySteps(served, fork.getId()));
        workflowRepository.incrementForkCount(source.getId());

        return WorkflowMapper.toWorkflowView(fork, steps, registry::isRemote, registry::isAvailable);
    }

    private static void refuse(ForkEligibility.Refusal refusal) {
        throw switch (refusal) {
            case OWN -> new UnprocessableEntityException(new LocalizedMessage("workflow.fork.own"),
                    "CANNOT_FORK_OWN");
            case FIXED -> new ConflictException(new LocalizedMessage("workflow.copy.fixedWorkflow"));
            case NOT_ALLOWED -> new LocalizedStatusException(HttpStatus.FORBIDDEN,
                    new LocalizedMessage("workflow.fork.notAllowed"));
        };
    }
}
