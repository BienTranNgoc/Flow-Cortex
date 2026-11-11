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

/** The arbiter every workflow write passes, and the definition check shared by every path that stores steps. */
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
     * Load under the row lock, then the owner and visibility rule (skipped for an admin), then the fixed Product
     * Video workflow, which nobody edits.
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

    /**
     * The same 404/403 rule as {@link #lockForWrite} for a read: no row lock, and the fixed Product Video workflow
     * stays readable.
     */
    Workflow requireOwned(Caller caller, UUID workflowId) {
        Workflow workflow = (caller.admin()
                ? workflowRepository.findById(workflowId).filter(found -> found.getDeletedAt() == null)
                : workflowRepository.findVisibleTo(workflowId, caller.userId()))
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        if (!caller.admin() && !workflow.getOwnerId().equals(caller.userId())) {
            throw new LocalizedStatusException(HttpStatus.FORBIDDEN, new LocalizedMessage("workflow.notOwner"));
        }
        return workflow;
    }

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
