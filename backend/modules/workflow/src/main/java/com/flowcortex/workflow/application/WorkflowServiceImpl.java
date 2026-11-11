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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementation of {@link WorkflowService}. */
@Service
class WorkflowServiceImpl implements WorkflowService, WorkflowAuthoring {

    /** Longest serialized step config accepted, in characters. */
    static final int MAX_CONFIG_CHARS = 50_000;

    private static final String ORDER_INVALID = "WORKFLOW_STEP_ORDER_INVALID";

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
        return workflowRepository.findAllVisibleTo(currentUserId).stream()
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
    public WorkflowView createDraft(UUID ownerId, String name, boolean shared) {
        Workflow workflow = workflowRepository.save(Workflow.draft(UUID.randomUUID(), ownerId, name, shared));
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
        if (update.shared() != null && update.shared() != workflow.isShared()) {
            workflow.setShared(update.shared());
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
        requireMayAuthor(caller, workflow, step.type());
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
        requireMayAuthor(caller, workflow, step.getType());
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

    @Override
    @Transactional
    public void deleteStep(UUID callerId, UUID workflowId, UUID stepId) {
        deleteStep(Caller.member(callerId), workflowId, stepId);
    }

    @Override
    @Transactional
    public WorkflowView reorderSteps(UUID callerId, UUID workflowId, List<UUID> stepIds) {
        return reorderSteps(Caller.member(callerId), workflowId, stepIds);
    }

    @Override
    @Transactional
    public void deleteStep(Caller caller, UUID workflowId, UUID stepId) {
        Workflow workflow = lockForWrite(caller, workflowId);
        List<WorkflowStep> steps = stepsOf(workflow);
        if (steps.stream().noneMatch(step -> step.getId().equals(stepId))) {
            throw new NotFoundException(new LocalizedMessage("workflow.definitionStep.notFound", stepId));
        }
        StepRenumbering.Outcome outcome = StepRenumbering.forDelete(rowsOf(steps), stepId);
        if (outcome instanceof StepRenumbering.Referenced referenced) {
            String readers = referenced.readers().stream().map(String::valueOf).collect(Collectors.joining(", "));
            throw new ConflictException(
                    new LocalizedMessage("workflow.definitionStep.referenced", referenced.deletedOrder(), readers));
        }
        List<StepRenumbering.Planned> planned = ((StepRenumbering.Plan) outcome).rows();
        validateDefinition(definitionOfPlan(planned));

        workflowStepRepository.deleteById(stepId);
        for (StepRenumbering.Planned step : planned) {
            if (step.orderChanged()) {
                workflowStepRepository.setOrder(step.row().id(), (short) step.row().order());
            }
        }
        applyReferenceRewrites(workflow, planned);
    }

    @Override
    @Transactional
    public WorkflowView reorderSteps(Caller caller, UUID workflowId, List<UUID> stepIds) {
        Workflow workflow = lockForWrite(caller, workflowId);
        List<WorkflowStep> steps = stepsOf(workflow);
        StepRenumbering.Outcome outcome = StepRenumbering.forOrder(rowsOf(steps), stepIds);
        switch (outcome) {
            case StepRenumbering.Unchanged unchanged -> {
                return WorkflowMapper.toWorkflowView(workflow, steps, registry::isRemote, registry::isAvailable);
            }
            case StepRenumbering.Stale stale ->
                throw new ConflictException(new LocalizedMessage("workflow.definitionStep.orderStale"));
            case StepRenumbering.Duplicate duplicate -> {
                LocalizedMessage reason = new LocalizedMessage("workflow.definitionStep.orderDuplicate");
                throw new UnprocessableEntityException(reason, ORDER_INVALID, Map.of("stepIds", reason));
            }
            case StepRenumbering.ForwardReference forward -> {
                LocalizedMessage reason = new LocalizedMessage("workflow.definitionStep.orderForward");
                Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
                forward.newIndexes().forEach(index -> errors.put("steps[" + index + "].inputRef", reason));
                throw new UnprocessableEntityException(reason, ORDER_INVALID, errors);
            }
            case StepRenumbering.Referenced referenced ->
                throw new IllegalStateException("A reorder cannot refuse a step as referenced");
            case StepRenumbering.Plan plan -> {
                validateDefinition(definitionOfPlan(plan.rows()));
                List<UUID> moved = plan.rows().stream()
                        .filter(StepRenumbering.Planned::orderChanged)
                        .map(step -> step.row().id())
                        .toList();
                workflowStepRepository.parkOrders(workflow.getId(), moved);
                for (StepRenumbering.Planned step : plan.rows()) {
                    if (step.orderChanged()) {
                        workflowStepRepository.setOrder(step.row().id(), (short) step.row().order());
                    }
                }
                List<WorkflowStep> reloaded = applyReferenceRewrites(workflow, plan.rows());
                return WorkflowMapper.toWorkflowView(workflow, reloaded, registry::isRemote, registry::isAvailable);
            }
        }
    }

    /**
     * Reloads the steps after the native order writes (they clear the persistence context) and gives each step
     * whose references the planner rewrote its new {@code inputRef} and {@code config}; dirty checking saves them.
     */
    private List<WorkflowStep> applyReferenceRewrites(Workflow workflow, List<StepRenumbering.Planned> planned) {
        List<WorkflowStep> reloaded = stepsOf(workflow);
        for (StepRenumbering.Planned step : planned) {
            if (!step.referencesChanged()) {
                continue;
            }
            reloaded.stream()
                    .filter(stored -> stored.getId().equals(step.row().id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Step vanished during renumbering: "
                            + step.row().id()))
                    .edit(step.row().name(), step.row().config(), step.row().inputRef());
        }
        return reloaded;
    }

    private static List<StepRenumbering.Row> rowsOf(List<WorkflowStep> steps) {
        return steps.stream()
                .map(step -> new StepRenumbering.Row(step.getId(), step.getStepOrder(), step.getType(),
                        step.getName(), step.getInputRef(), step.getConfig()))
                .toList();
    }

    private static List<NewWorkflowStep> definitionOfPlan(List<StepRenumbering.Planned> planned) {
        return planned.stream()
                .map(step -> new NewWorkflowStep(step.row().name(), step.row().type(), step.row().inputRef(),
                        step.row().config()))
                .toList();
    }

    /**
     * The one arbiter every write passes: load under the row lock, then the owner and visibility rule (skipped for
     * an admin), then the fixed Product Video workflow, which nobody edits.
     */
    private Workflow lockForWrite(Caller caller, UUID workflowId) {
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
     * A connector call step runs with the workflow owner's credential, so only the owner may add or edit one; an
     * admin passes {@link #lockForWrite} on any workflow but is refused here.
     */
    private static void requireMayAuthor(Caller caller, Workflow workflow, String stepType) {
        if (ConnectorStepOwnerGuard.isConnectorCall(stepType)
                && !ConnectorStepOwnerGuard.mayAuthor(caller.userId(), workflow.getOwnerId())) {
            throw new LocalizedStatusException(HttpStatus.FORBIDDEN, new LocalizedMessage("workflow.notOwner"));
        }
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
