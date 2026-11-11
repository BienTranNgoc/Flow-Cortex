package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ChatWorkflowView;
import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.StepEdit;
import com.flowcortex.workflow.api.UpdateWorkflow;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStatus;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link WorkflowService}. */
@Service
class WorkflowServiceImpl implements WorkflowService, WorkflowAuthoring {

    /** Longest serialized step config accepted, in characters. */
    static final int MAX_CONFIG_CHARS = 50_000;

    private static final ObjectMapper CONFIG_READER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final StepExecutorRegistry registry;
    private final Validator validator;
    private final CreditAccounts credits;

    WorkflowServiceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            StepExecutorRegistry registry, Validator validator, CreditAccounts credits) {
        this.credits = credits;
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.registry = registry;
        this.validator = validator;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowView> listVisibleWorkflows(UUID currentUserId) {
        return toViews(workflowRepository.findAllVisibleTo(currentUserId));
    }

    /** The one mapping of workflows to the views a member list shows, in the given order. */
    List<WorkflowView> toViews(List<Workflow> workflows) {
        return workflows.stream()
                .map(workflow -> WorkflowMapper.toWorkflowView(workflow, stepsOf(workflow), registry::isRemote,
                        registry::isAvailable))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatWorkflowView> listChatCatalog(UUID userId) {
        return listVisibleWorkflows(userId).stream()
                .map(view -> new ChatWorkflowView(view.id(), view.name(),
                        view.steps().stream().map(WorkflowStepView::type).toList(), inputKind(view),
                        view.estimatedCredits(credits)))
                .toList();
    }

    private static String inputKind(WorkflowView view) {
        if (ProductVideoWorkflow.ID.equals(view.id())) {
            return "product_video";
        }
        boolean imageFirst = !view.steps().isEmpty() && "remote".equals(view.steps().getFirst().mode());
        return imageFirst ? "image" : "text";
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowView getVisibleWorkflow(UUID currentUserId, UUID workflowId) {
        Workflow workflow = workflowRepository.findVisibleTo(workflowId, currentUserId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        return WorkflowMapper.toWorkflowView(workflow, stepsOf(workflow), registry::isRemote, registry::isAvailable);
    }

    @Override
    @Transactional
    public WorkflowView createWorkflow(UUID ownerId, String name, boolean shared, List<NewWorkflowStep> steps) {
        validateDefinition(steps);
        Workflow workflow = workflowRepository.save(new Workflow(UUID.randomUUID(), ownerId, name, shared));

        List<WorkflowStep> stepEntities = new ArrayList<>();
        short order = 1;
        for (NewWorkflowStep step : steps) {
            stepEntities.add(
                    new WorkflowStep(UUID.randomUUID(), workflow.getId(), order, step.name(), step.type(),
                            step.inputRef(), step.config()));
            order++;
        }
        stepEntities = workflowStepRepository.saveAll(stepEntities);

        return WorkflowMapper.toWorkflowView(workflow, stepEntities, registry::isRemote, registry::isAvailable);
    }

    @Override
    @Transactional
    public WorkflowView createDraft(UUID ownerId, String name) {
        Workflow workflow = workflowRepository.save(Workflow.draft(UUID.randomUUID(), ownerId, name));
        return WorkflowMapper.toWorkflowView(workflow, List.of(), registry::isRemote, registry::isAvailable);
    }

    // Self-invocation skips the proxy, so each member entry point opens the transaction the row lock needs.
    @Override
    @Transactional
    public WorkflowView updateWorkflow(UUID callerId, UUID workflowId, UpdateWorkflow update) {
        return updateWorkflow(Caller.member(callerId), workflowId, update);
    }

    @Override
    @Transactional
    public void deleteWorkflow(UUID callerId, UUID workflowId) {
        deleteWorkflow(Caller.member(callerId), workflowId);
    }

    @Override
    @Transactional
    public WorkflowStepView addStep(UUID callerId, UUID workflowId, NewStep step) {
        return addStep(Caller.member(callerId), workflowId, step);
    }

    @Override
    @Transactional
    public WorkflowStepView updateStep(UUID callerId, UUID workflowId, UUID stepId, StepEdit edit) {
        return updateStep(Caller.member(callerId), workflowId, stepId, edit);
    }

    @Override
    @Transactional
    public WorkflowView updateWorkflow(Caller caller, UUID workflowId, UpdateWorkflow update) {
        Workflow workflow = lockForWrite(caller, workflowId);
        if (update.name() != null && !update.name().equals(workflow.getName())) {
            workflow.rename(update.name());
        }
        if (update.status() != null) {
            WorkflowStatus status = WorkflowStatus.valueOf(update.status().toUpperCase(Locale.ROOT));
            if (status != workflow.getStatus()) {
                workflow.changeStatus(status);
            }
        }
        workflowRepository.save(workflow);
        return WorkflowMapper.toWorkflowView(workflow, stepsOf(workflow), registry::isRemote, registry::isAvailable);
    }

    @Override
    @Transactional
    public void deleteWorkflow(Caller caller, UUID workflowId) {
        Workflow workflow = lockForWrite(caller, workflowId);
        workflow.markDeleted();
        workflowRepository.save(workflow);
    }

    @Override
    @Transactional
    public WorkflowStepView addStep(Caller caller, UUID workflowId, NewStep step) {
        Workflow workflow = lockForWrite(caller, workflowId);
        int last = workflowStepRepository.findMaxStepOrder(workflow.getId());
        if (last >= Short.MAX_VALUE) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.definitionStep.tooMany"), null,
                    Map.of("steps", new LocalizedMessage("workflow.definitionStep.tooMany")));
        }
        requireObjectConfig(step.config());
        List<NewWorkflowStep> definition = new ArrayList<>(definitionOf(stepsOf(workflow)));
        definition.add(new NewWorkflowStep(step.name(), step.type(), step.inputRef(), step.config()));
        validateDefinition(definition);

        WorkflowStep saved = workflowStepRepository.save(new WorkflowStep(UUID.randomUUID(), workflow.getId(),
                (short) (last + 1), step.name(), step.type(), step.inputRef(), step.config()));
        return WorkflowMapper.toStepView(saved, registry::isRemote);
    }

    @Override
    @Transactional
    public WorkflowStepView updateStep(Caller caller, UUID workflowId, UUID stepId, StepEdit edit) {
        Workflow workflow = lockForWrite(caller, workflowId);
        WorkflowStep step = workflowStepRepository.findById(stepId)
                .filter(found -> found.getWorkflowId().equals(workflow.getId()))
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.definitionStep.notFound",
                        stepId)));
        requireObjectConfig(edit.config());
        String name = edit.name() == null ? step.getName() : emptyToNull(edit.name());
        String inputRef = edit.inputRef() == null ? step.getInputRef() : emptyToNull(edit.inputRef());
        String config = edit.config() == null ? step.getConfig() : edit.config();

        List<NewWorkflowStep> definition = stepsOf(workflow).stream()
                .map(other -> other.getId().equals(stepId)
                        ? new NewWorkflowStep(name, step.getType(), inputRef, config)
                        : new NewWorkflowStep(other.getName(), other.getType(), other.getInputRef(),
                                other.getConfig()))
                .toList();
        validateDefinition(definition);

        step.edit(name, config, inputRef);
        return WorkflowMapper.toStepView(workflowStepRepository.save(step), registry::isRemote);
    }

    /**
     * The one arbiter every write passes: load under the row lock, then the owner and visibility rule (skipped for
     * an admin), then the fixed Product Video workflow, which nobody edits.
     */
    Workflow lockForWrite(Caller caller, UUID workflowId) {
        Workflow workflow = workflowRepository.findByIdAndDeletedAtIsNull(workflowId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        requireOwnerOrAdmin(caller, workflow);
        if (ProductVideoWorkflow.ID.equals(workflow.getId())) {
            throw new ConflictException(new LocalizedMessage("workflow.system.readOnly"));
        }
        return workflow;
    }

    /** The owner and visibility rule of {@link #lockForWrite} for a read, without the row lock. */
    Workflow loadForOwnerRead(Caller caller, UUID workflowId) {
        Workflow workflow = workflowRepository.findActiveById(workflowId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        requireOwnerOrAdmin(caller, workflow);
        return workflow;
    }

    /** 404 for a caller who cannot see the workflow, 403 for one who can but does not own it. */
    private void requireOwnerOrAdmin(Caller caller, Workflow workflow) {
        if (caller.admin() || workflow.getOwnerId().equals(caller.userId())) {
            return;
        }
        if (workflowRepository.findVisibleTo(workflow.getId(), caller.userId()).isEmpty()) {
            throw new NotFoundException(new LocalizedMessage("workflow.notFound", workflow.getId()));
        }
        throw new LocalizedStatusException(HttpStatus.FORBIDDEN, new LocalizedMessage("workflow.notOwner"));
    }

    private void validateDefinition(List<NewWorkflowStep> steps) {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations =
                validator.validate(new KnownStepTypes.Definition(steps, registry.knownTypes(),
                        registry.storedInputTypes(), registry.remoteTypes()));
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }

    /** Runs before the step-list validation, so a config that is not an object is always a 422 on {@code config}. */
    private static void requireObjectConfig(String config) {
        if (config == null) {
            return;
        }
        if (config.length() > MAX_CONFIG_CHARS) {
            throw configRefused(new LocalizedMessage("workflow.definitionStep.configTooLarge", MAX_CONFIG_CHARS));
        }
        JsonNode parsed;
        try {
            parsed = CONFIG_READER.readTree(config);
        } catch (JsonProcessingException e) {
            throw configRefused(new LocalizedMessage("workflow.incomplete.configNotObject"));
        }
        if (!parsed.isObject()) {
            throw configRefused(new LocalizedMessage("workflow.incomplete.configNotObject"));
        }
    }

    private static UnprocessableEntityException configRefused(LocalizedMessage reason) {
        return new UnprocessableEntityException(reason, null, Map.of("config", reason));
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static List<NewWorkflowStep> definitionOf(List<WorkflowStep> steps) {
        return steps.stream()
                .map(step -> new NewWorkflowStep(step.getName(), step.getType(), step.getInputRef(), step.getConfig()))
                .toList();
    }

    private List<WorkflowStep> stepsOf(Workflow workflow) {
        return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId());
    }
}
