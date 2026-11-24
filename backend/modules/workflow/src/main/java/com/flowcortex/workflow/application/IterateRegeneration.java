package com.flowcortex.workflow.application;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.application.IterateRegenerateRules.Child;
import com.flowcortex.workflow.application.IterateRegenerateRules.ReArm;
import com.flowcortex.workflow.application.IterateRegenerateRules.Redispatch;
import com.flowcortex.workflow.application.IterateRegenerateRules.Refused;
import com.flowcortex.workflow.application.IterateRegenerateRules.Verdict;
import com.flowcortex.workflow.application.RunResumeRules.StepState;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A member running one item of a completed iterate step again: the item moves to its next attempt and
 * is dispatched after commit; the run, the step and its other items stay as they are, and the step's
 * output follows when the item completes. {@link IterateRegenerateRules} decides which request is
 * refused; the write is taken under the run's row lock so two callers racing on one item have one winner.
 */
@Service
public class IterateRegeneration {

    private static final Logger LOG = LoggerFactory.getLogger(IterateRegeneration.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final StepExecutorRegistry registry;
    private final MediaJobs mediaJobs;
    private final CreditAccounts creditAccounts;
    private final RunAdvancer advancer;
    private final TransactionTemplate transactionTemplate;
    private final RunUpdates runUpdates;
    private final ApplicationEventPublisher events;
    private final boolean workflowEnabled;

    IterateRegeneration(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            StepExecutorRegistry registry, MediaJobs mediaJobs, CreditAccounts creditAccounts, RunAdvancer advancer,
            TransactionTemplate transactionTemplate, RunUpdates runUpdates, ApplicationEventPublisher events,
            @Value("${flowcortex.workflow.enabled:true}") boolean workflowEnabled) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.registry = registry;
        this.mediaJobs = mediaJobs;
        this.creditAccounts = creditAccounts;
        this.advancer = advancer;
        this.transactionTemplate = transactionTemplate;
        this.runUpdates = runUpdates;
        this.events = events;
        this.workflowEnabled = workflowEnabled;
    }

    /**
     * Runs the item at {@code itemIndex} of the iterate step {@code stepId} again as its next attempt; an item
     * still {@code pending} from an earlier regenerate is dispatched again without a new attempt.
     * @return the run as it is once the item is re-armed
     * @throws LocalizedStatusException 503 when workflows are switched off or the item's type is unavailable
     * @throws NotFoundException if the run is not the caller's, the step is not a step of it or it has no such item
     * @throws ConflictException if the run, the step or the item is not in a state that accepts it, or the step is
     *     already running as many items as it may
     * @throws InsufficientCreditsException 402 when the balance is below the item type's price
     */
    public RunView regenerate(UUID callerId, UUID runId, UUID stepId, int itemIndex) {
        if (!workflowEnabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.disabled"));
        }
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
        Optional<RunStep> step = runStepRepository.findByRunIdOrderByStepOrderAsc(runId).stream()
                .filter(candidate -> candidate.getId().equals(stepId))
                .findFirst();
        List<RunStep> items = step.filter(parent -> IterateStepConfig.TYPE.equals(parent.getType()))
                .map(parent -> runStepRepository.findChildren(parent.getId(), parent.getAttempt()))
                .orElse(List.of());
        int limit = step.map(this::effectiveConcurrency).orElse(1);

        Verdict verdict = IterateRegenerateRules.verdict(run.getStatus(),
                step.map(parent -> new StepState(parent.getId(), parent.getStepOrder(), parent.getType(),
                        parent.getStatus(), parent.getErrorCode(), parent.getAttempt())),
                items.stream()
                        .map(item -> new Child(item.getId(), item.getItemIndex(), item.getStatus(), item.getAttempt()))
                        .toList(),
                itemIndex, limit);
        if (verdict instanceof Refused refused) {
            LOG.info("Iterate regenerate: outcome=refused kind={} runId={} stepId={} itemIndex={}", refused.kind(),
                    runId, stepId, itemIndex);
            throw refusal(refused.kind(), stepId);
        }
        RunStep parent = step.orElseThrow();
        RunStep item = items.stream().filter(candidate -> candidate.getItemIndex() == itemIndex).findFirst()
                .orElseThrow();
        requirePayable(callerId, item.getType());

        transactionTemplate.executeWithoutResult(status -> {
            workflowRunRepository.lockById(runId);
            if (verdict instanceof ReArm reArm
                    && runStepRepository.reArmChild(reArm.childId(), reArm.attempt()) == 0) {
                LOG.info("Iterate regenerate: outcome=child_running runId={} stepId={} itemIndex={}", runId, stepId,
                        itemIndex);
                throw new ConflictException(new LocalizedMessage("workflow.iterate.childRunning"));
            }
            // The item itself is pending by now, so it is counted: more than the cap means a sibling is in the way.
            if (runStepRepository.countActiveChildren(parent.getId(), parent.getAttempt()) > limit) {
                LOG.info("Iterate regenerate: outcome=busy runId={} stepId={} itemIndex={} limit={}", runId, stepId,
                        itemIndex, limit);
                throw new ConflictException(new LocalizedMessage("workflow.iterate.busy"));
            }
            runUpdates.touched(runId);
            events.publishEvent(new IterateChildRegenerated(runId, item.getId()));
        });
        LOG.info("Iterate regenerate: outcome={} runId={} stepId={} itemIndex={} childId={}",
                verdict instanceof Redispatch ? "redispatched" : "regenerated", runId, stepId, itemIndex,
                item.getId());
        return currentView(callerId, runId);
    }

    /** The step's concurrency as the engine applies it; a config that cannot be read keeps the strictest cap. */
    private int effectiveConcurrency(RunStep parent) {
        if (!IterateStepConfig.TYPE.equals(parent.getType())) {
            return 1;
        }
        try {
            return advancer.effectiveConcurrency(IterateStepConfig.parse(parent.getConfig()));
        } catch (IllegalArgumentException e) {
            LOG.error("Iterate step {} of run {} holds a config it cannot read; regenerating at concurrency 1",
                    parent.getId(), parent.getRunId(), e);
            return 1;
        }
    }

    private void requirePayable(UUID callerId, String itemType) {
        if (!registry.isAvailable(itemType)) {
            throw new DubDisabledException();
        }
        if (!creditAccounts.enabled()) {
            return;
        }
        long balance = creditAccounts.balance(callerId);
        long required = creditAccounts.estimate(List.of(itemType));
        if (balance < required) {
            throw new InsufficientCreditsException(balance, required);
        }
    }

    private RuntimeException refusal(IterateRegenerateRules.Kind kind, UUID stepId) {
        return switch (kind) {
            case STEP_NOT_TOP_LEVEL ->
                    new NotFoundException(new LocalizedMessage("workflow.runStep.notFound", stepId));
            case NOT_ITERATE -> new ConflictException(new LocalizedMessage("workflow.iterate.notIterate"));
            case RUN_NOT_COMPLETED -> new ConflictException(new LocalizedMessage("workflow.iterate.runNotCompleted"));
            case STEP_NOT_COMPLETED ->
                    new ConflictException(new LocalizedMessage("workflow.iterate.stepNotCompleted"));
            case ITEM_NOT_FOUND -> new NotFoundException(new LocalizedMessage("workflow.iterate.itemNotFound"));
            case CHILD_RUNNING -> new ConflictException(new LocalizedMessage("workflow.iterate.childRunning"));
            case BUSY -> new ConflictException(new LocalizedMessage("workflow.iterate.busy"));
            case ATTEMPT_LIMIT -> new ConflictException(new LocalizedMessage("workflow.runStep.attemptLimit"));
        };
    }

    private RunView currentView(UUID callerId, UUID runId) {
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, callerId).orElseThrow();
        return WorkflowMapper.toRunView(run, runStepRepository.findByRunIdOrderByStepOrderAsc(runId),
                runStepRepository.findCurrentChildren(runId), registry::isRemote, mediaJobs::isDownloadable);
    }
}
