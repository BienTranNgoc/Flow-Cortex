package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStatus;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The one arbiter every workflow write passes: the row-locking load with the owner and visibility rule, and the
 * rule a step list must meet before it is stored. Callers must already run inside a transaction.
 */
@Component
class WorkflowWriteAccess {

    private final WorkflowRepository workflowRepository;
    private final StepExecutorRegistry registry;
    private final Validator validator;

    WorkflowWriteAccess(WorkflowRepository workflowRepository, StepExecutorRegistry registry, Validator validator) {
        this.workflowRepository = workflowRepository;
        this.registry = registry;
        this.validator = validator;
    }

    /**
     * Loads under the row lock, then applies the owner and visibility rule (skipped for an admin), then refuses the
     * fixed Product Video workflow, which nobody edits.
     */
    Workflow lockForWrite(Caller caller, UUID workflowId) {
        Workflow workflow = workflowRepository.findByIdAndDeletedAtIsNull(workflowId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        if (!caller.admin() && !workflow.getOwnerId().equals(caller.userId())) {
            boolean visible = workflow.isShared() && workflow.getStatus() == WorkflowStatus.PUBLISHED;
            if (!visible) {
                throw new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId));
            }
            throw new LocalizedStatusException(HttpStatus.FORBIDDEN, new LocalizedMessage("workflow.notOwner"));
        }
        if (ProductVideoWorkflow.ID.equals(workflow.getId())) {
            throw new ConflictException(new LocalizedMessage("workflow.system.readOnly"));
        }
        return workflow;
    }

    /** @throws ConstraintViolationException one violation per step the engine cannot run, at {@code steps[i]} */
    void validateDefinition(List<NewWorkflowStep> steps) {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations =
                validator.validate(new KnownStepTypes.Definition(steps, registry.knownTypes(),
                        registry.storedInputTypes(), registry.remoteTypes()));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    static List<NewWorkflowStep> definitionOf(List<WorkflowStep> steps) {
        return steps.stream()
                .map(step -> new NewWorkflowStep(step.getName(), step.getType(), step.getInputRef(), step.getConfig()))
                .toList();
    }
}
