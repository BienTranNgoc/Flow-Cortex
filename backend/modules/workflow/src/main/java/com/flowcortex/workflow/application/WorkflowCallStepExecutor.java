package com.flowcortex.workflow.application;

import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code workflow.call} — starts another workflow as a child run, for the step's owner, inside the step's claim.
 * The facts are read in their own read-only transaction, so a failing read cannot mark the claim rollback-only
 * and every refusal can still fail the step.
 */
@Component
class WorkflowCallStepExecutor implements StepExecutor {

    static final String TYPE = "workflow.call";

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowCallStepExecutor.class);
    private static final String UNAVAILABLE = "workflow_call_unavailable";
    private static final int MAX_ANCESTOR_READS = WorkflowCallRules.MAX_PARENT_DEPTH + 1;

    private final WorkflowRunRepository runs;
    private final WorkflowRepository workflows;
    private final ServedDefinitions served;
    private final ObjectProvider<RunStartGuards> guards;
    private final ObjectProvider<StepExecutorRegistry> registry;
    private final MediaJobs mediaJobs;
    private final RunCreation creation;
    private final TransactionTemplate factsReads;
    private final boolean workflowsEnabled;
    private final Duration maxRuntime;

    WorkflowCallStepExecutor(WorkflowRunRepository runs, WorkflowRepository workflows, ServedDefinitions served,
            ObjectProvider<RunStartGuards> guards, ObjectProvider<StepExecutorRegistry> registry, MediaJobs mediaJobs,
            RunCreation creation, TransactionTemplate transactionTemplate,
            @Value("${flowcortex.workflow.enabled:true}") boolean workflowsEnabled,
            @Value("${flowcortex.workflow.call.max-runtime}") Duration maxRuntime) {
        this.runs = runs;
        this.workflows = workflows;
        this.served = served;
        this.guards = guards;
        this.registry = registry;
        this.mediaJobs = mediaJobs;
        this.creation = creation;
        this.factsReads = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.factsReads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.factsReads.setReadOnly(true);
        this.workflowsEnabled = workflowsEnabled;
        this.maxRuntime = maxRuntime;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mode mode() {
        return Mode.NESTED;
    }

    @Override
    public Duration maxRuntime() {
        return maxRuntime;
    }

    @Override
    public String execute(StepContext context) {
        WorkflowCallConfig.Result parsed = WorkflowCallConfig.parse(context.config());
        if (parsed.config() == null) {
            throw new StepFailedException("workflow_call_invalid_config", parsed.fault());
        }
        RunCreation.NewRun child;
        try {
            child = factsReads.execute(status -> prepare(context, parsed.config().workflowId()));
        } catch (StepFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Could not prepare the call of workflow {} from run {}", parsed.config().workflowId(),
                    context.runId(), e);
            throw new StepFailedException(UNAVAILABLE, "The called workflow cannot run right now", e);
        }
        creation.create(child);
        LOG.info("Child run started: parentRunId={} childRunId={} workflowId={} depth={}", context.runId(),
                child.runId(), child.workflowId(), child.callDepth());
        return null;
    }

    private RunCreation.NewRun prepare(StepContext context, UUID namedId) {
        WorkflowRun parent = runs.findById(context.runId())
                .orElseThrow(() -> new IllegalStateException("run " + context.runId() + " is gone"));
        UUID ownerId = parent.getOwnerId();
        List<UUID> ancestors = ancestorWorkflowIds(parent);
        int parentDepth = parent.getCallDepth();

        refuseIf(WorkflowCallRules.refusal(new WorkflowCallRules.CallFacts(namedId, parent.getWorkflowId(),
                ancestors, parentDepth, true, null, List.of(TYPE), false, false, false, true, true)));

        StepExecutorRegistry executors = registry.getObject();
        Optional<Workflow> found = workflows.findVisibleTo(namedId, ownerId);
        List<WorkflowStep> steps = found.isPresent() ? served.stepsFor(found.get(), ownerId) : List.of();
        List<String> types = steps.stream().map(WorkflowStep::getType).toList();
        refuseIf(WorkflowCallRules.refusal(new WorkflowCallRules.CallFacts(namedId, parent.getWorkflowId(),
                ancestors, parentDepth, found.isPresent(), ProductVideoWorkflow.ID, types,
                !types.isEmpty() && executors.isRemote(types.getFirst()), types.stream().anyMatch(executors::isRemote),
                types.stream().anyMatch(type -> !executors.isAvailable(type)), workflowsEnabled,
                mediaJobs.isEnabled())));

        Workflow workflow = found.orElseThrow();
        checkGuards(ownerId, workflow, steps);
        return RunCreation.NewRun.child(workflow.getId(), ownerId, steps, context.input(), parent.getOptions(),
                parent.getId(), context.stepId(), context.attempt(), (short) (parentDepth + 1));
    }

    private List<UUID> ancestorWorkflowIds(WorkflowRun parent) {
        List<UUID> ids = new ArrayList<>();
        UUID next = parent.getParentRunId();
        while (next != null && ids.size() < MAX_ANCESTOR_READS) {
            WorkflowRun ancestor = runs.findById(next)
                    .orElseThrow(() -> new IllegalStateException("an ancestor run is gone"));
            ids.add(ancestor.getWorkflowId());
            next = ancestor.getParentRunId();
        }
        return ids;
    }

    private void checkGuards(UUID ownerId, Workflow workflow, List<WorkflowStep> steps) {
        try {
            guards.getObject().check(ownerId, workflow.getId(), steps, new StartContext(true));
        } catch (InsufficientCreditsException e) {
            throw new StepFailedException(InsufficientCreditsException.CODE,
                    "Not enough credits to run the called workflow", e);
        } catch (UnprocessableEntityException e) {
            if (WorkflowReadinessGuard.CODE.equals(e.getCode())) {
                throw new StepFailedException("workflow_call_child_incomplete",
                        "The called workflow is not complete", e);
            }
            throw e;
        }
    }

    private static void refuseIf(WorkflowCallRules.Refusal refusal) {
        if (refusal != null) {
            throw new StepFailedException(refusal.code(), refusal.message());
        }
    }
}
