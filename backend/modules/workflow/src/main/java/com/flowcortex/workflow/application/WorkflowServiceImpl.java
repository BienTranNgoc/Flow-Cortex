package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link WorkflowService} (design.md D1, R-049). */
@Service
class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;

    WorkflowServiceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowView> listVisibleWorkflows(UUID currentUserId) {
        return workflowRepository.findAllVisibleTo(currentUserId).stream()
                .map(workflow -> WorkflowMapper.toWorkflowView(workflow, stepsOf(workflow)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowView getVisibleWorkflow(UUID currentUserId, UUID workflowId) {
        Workflow workflow = workflowRepository.findVisibleTo(workflowId, currentUserId)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + workflowId));
        return WorkflowMapper.toWorkflowView(workflow, stepsOf(workflow));
    }

    @Override
    @Transactional
    public WorkflowView createWorkflow(UUID ownerId, String name, boolean shared, List<NewWorkflowStep> steps) {
        Workflow workflow = workflowRepository.save(new Workflow(UUID.randomUUID(), ownerId, name, shared));

        List<WorkflowStep> stepEntities = new ArrayList<>();
        short order = 1;
        for (NewWorkflowStep step : steps) {
            stepEntities.add(
                    new WorkflowStep(UUID.randomUUID(), workflow.getId(), order, step.name(), step.type(),
                            step.inputRef()));
            order++;
        }
        stepEntities = workflowStepRepository.saveAll(stepEntities);

        return WorkflowMapper.toWorkflowView(workflow, stepEntities);
    }

    private List<WorkflowStep> stepsOf(Workflow workflow) {
        return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId());
    }
}
