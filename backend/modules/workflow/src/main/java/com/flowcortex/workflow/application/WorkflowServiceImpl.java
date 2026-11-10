package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.api.NewWorkflowStep;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
    private final WorkflowWriteAccess writeAccess;
    private final VersionWriter versionWriter;
    private final ServedDefinitions servedDefinitions;

    WorkflowServiceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            StepExecutorRegistry registry, WorkflowWriteAccess writeAccess, VersionWriter versionWriter,
            ServedDefinitions servedDefinitions) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.registry = registry;
        this.writeAccess = writeAccess;
        this.versionWriter = versionWriter;
        this.servedDefinitions = servedDefinitions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowView> listVisibleWorkflows(UUID currentUserId) {
        List<Workflow> workflows = workflowRepository.findAllVisibleTo(currentUserId);
        Map<UUID, ServedDefinitions.ServedSteps> served = servedDefinitions.servedFor(workflows, currentUserId);
        return workflows.stream()
                .map(workflow -> servedView(workflow, served.get(workflow.getId()), currentUserId))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowView getVisibleWorkflow(UUID currentUserId, UUID workflowId) {
        Workflow workflow = workflowRepository.findVisibleTo(workflowId, currentUserId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        return servedView(workflow, servedDefinitions.servedFor(List.of(workflow), currentUserId).get(workflowId),
                currentUserId);
    }

    /**
     * What a reader sees: the served steps, and for a member who does not own the workflow only each step's id,
     * order, name, type and mode (the owner's prompts stay with the owner). A built-in workflow has no private
     * author, so its steps are shown in full.
     */
    private WorkflowView servedView(Workflow workflow, ServedDefinitions.ServedSteps served, UUID viewerId) {
        WorkflowView view = WorkflowMapper.toWorkflowView(workflow, served.steps(), registry::isRemote,
                registry::isAvailable, workflow.getPublishedVersionId(), served.version());
        boolean ownerOrBuiltIn = workflow.isSystemOwned() || workflow.getOwnerId().equals(viewerId);
        return ownerOrBuiltIn ? view : WorkflowMapper.withoutStepContent(view);
    }

    @Override
    @Transactional
    public WorkflowView createWorkflow(UUID ownerId, String name, boolean shared, List<NewWorkflowStep> steps) {
        writeAccess.validateDefinition(steps);
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
        Workflow workflow = writeAccess.lockForWrite(caller, workflowId);
        if (update.name() != null && !update.name().equals(workflow.getName())) {
            workflow.rename(update.name());
        }
        if (update.shared() != null && update.shared() != workflow.isShared()) {
            workflow.setShared(update.shared());
        }
        if (update.allowFork() != null && update.allowFork() != workflow.isAllowFork()) {
            workflow.setAllowFork(update.allowFork());
        }
        if (update.status() != null) {
            WorkflowStatus status = WorkflowStatus.valueOf(update.status().toUpperCase(Locale.ROOT));
            if (status != workflow.getStatus()) {
                workflow.changeStatus(status);
            }
        }
        workflowRepository.save(workflow);
        versionWriter.ensurePublishedPointer(workflow, caller.userId());
        return WorkflowMapper.toWorkflowView(workflow, stepsOf(workflow), registry::isRemote, registry::isAvailable,
                workflow.getPublishedVersionId(), null);
    }

    @Override
    @Transactional
    public void deleteWorkflow(Caller caller, UUID workflowId) {
        Workflow workflow = writeAccess.lockForWrite(caller, workflowId);
        workflow.markDeleted();
        workflowRepository.save(workflow);
    }

    @Override
    @Transactional
    public WorkflowStepView addStep(Caller caller, UUID workflowId, NewStep step) {
        Workflow workflow = writeAccess.lockForWrite(caller, workflowId);
        int last = workflowStepRepository.findMaxStepOrder(workflow.getId());
        if (last >= Short.MAX_VALUE) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.definitionStep.tooMany"), null,
                    Map.of("steps", new LocalizedMessage("workflow.definitionStep.tooMany")));
        }
        requireObjectConfig(step.config());
        List<NewWorkflowStep> definition = new ArrayList<>(WorkflowWriteAccess.definitionOf(stepsOf(workflow)));
        definition.add(new NewWorkflowStep(step.name(), step.type(), step.inputRef(), step.config()));
        writeAccess.validateDefinition(definition);

        WorkflowStep saved = workflowStepRepository.save(new WorkflowStep(UUID.randomUUID(), workflow.getId(),
                (short) (last + 1), step.name(), step.type(), step.inputRef(), step.config()));
        return WorkflowMapper.toStepView(saved, registry::isRemote);
    }

    @Override
    @Transactional
    public WorkflowStepView updateStep(Caller caller, UUID workflowId, UUID stepId, StepEdit edit) {
        Workflow workflow = writeAccess.lockForWrite(caller, workflowId);
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
        writeAccess.validateDefinition(definition);

        step.edit(name, config, inputRef);
        return WorkflowMapper.toStepView(workflowStepRepository.save(step), registry::isRemote);
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

    private List<WorkflowStep> stepsOf(Workflow workflow) {
        return workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId());
    }
}
