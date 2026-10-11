package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link WorkflowService}. */
@Service
class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final StepExecutorRegistry registry;
    private final Validator validator;

    WorkflowServiceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            StepExecutorRegistry registry, Validator validator) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.registry = registry;
        this.validator = validator;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowView> listVisibleWorkflows(UUID currentUserId) {
        return workflowRepository.findAllVisibleTo(currentUserId).stream()
                .map(workflow -> WorkflowMapper.toWorkflowView(workflow, stepsOf(workflow), registry::isRemote))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowView getVisibleWorkflow(UUID currentUserId, UUID workflowId) {
        Workflow workflow = workflowRepository.findVisibleTo(workflowId, currentUserId)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + workflowId));
        return WorkflowMapper.toWorkflowView(workflow, stepsOf(workflow), registry::isRemote);
    }

    @Override
    @Transactional
    public WorkflowView createWorkflow(UUID ownerId, String name, boolean shared, List<NewWorkflowStep> steps) {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations =
                validator.validate(new KnownStepTypes.Definition(steps, registry.knownTypes(),
                        registry.storedInputTypes()));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
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

        return WorkflowMapper.toWorkflowView(workflow, stepEntities, registry::isRemote);
    }

    private List<WorkflowStep> stepsOf(Workflow workflow) {
        return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId());
    }
}
