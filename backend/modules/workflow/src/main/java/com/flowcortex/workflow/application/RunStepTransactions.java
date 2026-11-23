package com.flowcortex.workflow.application;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short REQUIRES_NEW transactions a step's life splits across: claim, pickup, lease
 * extension, then complete or fail. No step work runs inside them — an inline step runs on an
 * executor thread between pickup and completion, a remote one on the worker. Each method must be
 * called through this bean's Spring proxy (never {@code this.method()} from {@link RunAdvancer}
 * — that skips the proxy and joins the caller's transaction), so a transition commits on its own.
 *
 * <p>Every transition after the claim is a row-count CAS on {@code status = 'running'} and the
 * step's attempt ({@code RunStepRepository}); a method returns {@code false} when it lost that CAS
 * and then changes nothing else. A failure copies the step's message onto the run, which the
 * admin pages show. An item of an iterate step ends through the same transitions but never marks
 * the run: the iterate step reads its items and decides.
 */
@Component
class RunStepTransactions {

    /** How a claim ended: someone else holds the step, it was handed on, or it failed in the claim. */
    enum ClaimOutcome {
        LOST,
        DISPATCHED,
        FAILED
    }

    /** How a failure ended: the step was no longer running, it was failed, or failing it settled a stopped run. */
    private enum Failure {
        LOST,
        FAILED,
        SETTLED
    }

    static final String NOT_ENOUGH_CREDITS = "Not enough credits to run this step";
    static final String ITERATE_CANCELLED = "iterate_cancelled";
    static final String ITERATE_CANCELLED_MESSAGE = "The run was stopped before every item ran";

    private static final Logger LOG = LoggerFactory.getLogger(RunStepTransactions.class);

    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final CreditAccounts creditAccounts;
    private final RunUpdates runUpdates;
    private final ApplicationEventPublisher events;
    private final ChildRuns childRuns;

    RunStepTransactions(RunStepRepository runStepRepository, WorkflowRunRepository workflowRunRepository,
            CreditAccounts creditAccounts, RunUpdates runUpdates, ApplicationEventPublisher events,
            ChildRuns childRuns) {
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.creditAccounts = creditAccounts;
        this.runUpdates = runUpdates;
        this.events = events;
        this.childRuns = childRuns;
    }

    /**
     * Runs a callback in this bean's own {@code REQUIRES_NEW} transaction, whose persistence context
     * has nothing already loaded in it. {@link RunAdvancer} reads the step ladder right after it
     * closes a step, and that read inherits whatever the caller had loaded — the step still sits in
     * the first-level cache at its pre-transition status. An {@code @Transactional} method on
     * {@code RunAdvancer} cannot fix that: every call to it is a self-invocation that skips the
     * proxy, exactly as this class's own Javadoc warns for its methods.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void inNewTransaction(Runnable action) {
        action.run();
    }

    /** {@link #inNewTransaction} for an action that answers, e.g. whether the run should be read again. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    <T> T callInNewTransaction(Supplier<T> action) {
        return action.get();
    }

    /**
     * {@code pending -> running}, atomically, then the step's charge and {@code dispatch} inside the
     * same transaction — a remote job is recorded for publication with the claim and the charge, so
     * none exists without the others. A member who cannot pay for the step gets it errored with
     * {@code INSUFFICIENT_CREDITS} instead of dispatched. A {@link StepFailedException} from
     * {@code dispatch} errors the step in this transaction; any other exception rolls the claim back
     * and propagates.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ClaimOutcome claim(UUID runId, UUID stepId, short attempt, Instant now, Instant queueDeadline,
            String resolvedInput, Runnable dispatch) {
        workflowRunRepository.lockById(runId);
        if (runStepRepository.claimPending(stepId, now, queueDeadline, resolvedInput) != 1) {
            return ClaimOutcome.LOST;
        }
        return claimed(runId, stepId, attempt, now, dispatch);
    }

    /**
     * {@link #claim} for one item of the iterate step {@code parentId}: it starts only while fewer than
     * {@code limit} items of {@code parentAttempt} run, counted inside the claiming statement after the
     * run's lock, so racing claims of one run cannot both take the last slot. A {@code regenerated} item
     * belongs to a completed run and parent, a normal one to a running run and parent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ClaimOutcome claimChild(UUID runId, UUID childId, short attempt, Instant now, Instant queueDeadline,
            String resolvedInput, UUID parentId, short parentAttempt, int limit, boolean regenerated,
            Runnable dispatch) {
        workflowRunRepository.lockById(runId);
        int claimed = regenerated
                ? runStepRepository.claimRegeneratedChild(childId, parentId, parentAttempt, limit, now,
                        queueDeadline, resolvedInput)
                : runStepRepository.claimPendingChild(childId, parentId, parentAttempt, limit, now, queueDeadline,
                        resolvedInput);
        if (claimed != 1) {
            return ClaimOutcome.LOST;
        }
        return claimed(runId, childId, attempt, now, dispatch);
    }

    private ClaimOutcome claimed(UUID runId, UUID stepId, short attempt, Instant now, Runnable dispatch) {
        runUpdates.touched(runId);
        if (!charge(runId, stepId, attempt)) {
            failRunning(runId, stepId, attempt, InsufficientCreditsException.CODE, NOT_ENOUGH_CREDITS, now);
            return ClaimOutcome.FAILED;
        }
        try {
            dispatch.run();
            return ClaimOutcome.DISPATCHED;
        } catch (StepFailedException e) {
            failRunning(runId, stepId, attempt, e.code(), e.getMessage(), now);
            return ClaimOutcome.FAILED;
        }
    }

    /** A delivery's pickup; {@code false} if the step is no longer running at this attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean recordPickup(UUID runId, UUID stepId, short attempt, Instant at, Instant leaseUntil, Instant runDeadline,
            short deliveryCount) {
        if (runStepRepository.recordPickup(stepId, attempt, at, leaseUntil, runDeadline, deliveryCount) != 1) {
            return false;
        }
        runUpdates.touched(runId);
        return true;
    }

    /**
     * A heartbeat; {@code false} if the step is no longer running at this attempt. A beat without a
     * {@code percent} moves only the lease, which no run read shows, so it is not signalled.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean extendLease(UUID runId, UUID stepId, short attempt, Instant now, Instant leaseUntil, Short percent,
            short deliveryCount, Instant runDeadlineIfUnset) {
        if (runStepRepository.extendLease(stepId, attempt, now, leaseUntil, percent, deliveryCount,
                runDeadlineIfUnset) != 1) {
            return false;
        }
        if (percent != null) {
            runUpdates.touched(runId);
        }
        return true;
    }

    /** {@code running -> completed}; marks the owning run {@code completed} too if this was the last step. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean complete(UUID runId, UUID stepId, short attempt, String output, String outputs, Instant now) {
        return complete(runId, stepId, attempt, output, outputs, now, () -> { });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean complete(UUID runId, UUID stepId, short attempt, String output, String outputs, Instant now,
            Runnable afterAccepted) {
        workflowRunRepository.lockById(runId);
        if (runStepRepository.completeIfRunning(stepId, runId, attempt, output, outputs, now) != 1) {
            return false;
        }
        runUpdates.touched(runId);
        afterAccepted.run();
        Optional<RunStepRepository.Lineage> lineage = runStepRepository.findLineageById(stepId);
        if (isItem(lineage)) {
            childClosed(runId, stepId, lineage.get(), true, now);
            return true;
        }
        completeRunIfAllStepsCompleted(runId, now);
        return true;
    }

    /**
     * {@code running -> error}; marks the owning run {@code error} — halts further dispatch — or, when the
     * run is being stopped, settles it {@code cancelled} instead. An item of an iterate step leaves its run
     * as it is: the iterate step decides what its failure means.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean fail(UUID runId, UUID stepId, short attempt, String code, String message, Instant now) {
        workflowRunRepository.lockById(runId);
        return failRunning(runId, stepId, attempt, code, message, now) != Failure.LOST;
    }

    /**
     * Fails every running iterate step of a run being stopped that has no item running, so the stop does
     * not wait for an end that will not come. Joins the caller's transaction, which already holds the
     * run's lock through the update that began the stop.
     *
     * @return whether failing them settled the run {@code cancelled}
     */
    boolean failIdleIterateParents(UUID runId, Instant now) {
        boolean settled = false;
        for (RunStepRepository.IdleParent parent : runStepRepository.findIdleIterateParents(runId)) {
            settled |= failRunning(runId, parent.getId(), parent.getAttempt(), ITERATE_CANCELLED,
                    ITERATE_CANCELLED_MESSAGE, now) == Failure.SETTLED;
        }
        return settled;
    }

    /** Completes a still-running run whose steps are all completed (a redelivered start after the last result). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void completeRunIfDone(UUID runId, Instant now) {
        workflowRunRepository.lockById(runId);
        completeRunIfAllStepsCompleted(runId, now);
    }

    /**
     * Expects the caller to hold the run's lock ({@link WorkflowRunRepository#lockById}). The step's
     * charge goes back only on the failure that won the CAS, and only for a system failure.
     */
    private Failure failRunning(UUID runId, UUID stepId, short attempt, String code, String message, Instant now) {
        if (runStepRepository.failIfRunning(stepId, runId, attempt, code, message, now) != 1) {
            return Failure.LOST;
        }
        runUpdates.touched(runId);
        creditAccounts.refundStep(runId, stepId, attempt, code);
        Optional<RunStepRepository.Lineage> lineage = runStepRepository.findLineageById(stepId);
        if (isItem(lineage)) {
            return childClosed(runId, stepId, lineage.get(), false, now);
        }
        if (lineage.map(l -> IterateStepConfig.TYPE.equals(l.getType())).orElse(false)) {
            runStepRepository.skipPendingChildren(stepId);
        }
        childRuns.stopChildOfStep(stepId);
        if (settleIfCancelling(runId, now)) {
            return Failure.SETTLED;
        }
        WorkflowRun run = requireRun(runId);
        if (run.markError(message, now)) {
            workflowRunRepository.save(run);
            runUpdates.touched(runId);
            childRuns.finished(runId);
            LOG.info("Workflow run finished: runId={} status=error code={}", runId, code);
        }
        return Failure.FAILED;
    }

    /**
     * An item of an iterate step has ended; the caller holds the run's lock. A completed iterate step
     * takes a completed item's new result in place of the old one and ignores a failed item. A run being
     * stopped ends once no item runs: through the iterate step's own failure when it is still running,
     * else by settling the run.
     */
    private Failure childClosed(UUID runId, UUID childId, RunStepRepository.Lineage child, boolean completed,
            Instant now) {
        UUID parentId = child.getParentStepId();
        RunStepRepository.StepResult parent = runStepRepository.findResultById(parentId).orElse(null);
        if (parent == null) {
            return Failure.FAILED;
        }
        if (parent.getStatus() == RunStepStatus.COMPLETED) {
            if (completed) {
                regather(runId, parentId, parent, childId);
            }
            return Failure.FAILED;
        }
        if (requireRun(runId).getStatus() != RunStatus.CANCELLING) {
            return Failure.FAILED;
        }
        boolean current = parent.getStatus() == RunStepStatus.RUNNING
                && child.getParentAttempt() != null && child.getParentAttempt() == parent.getAttempt();
        if (current && runStepRepository.countRunningChildren(parentId, parent.getAttempt()) == 0) {
            return failRunning(runId, parentId, parent.getAttempt(), ITERATE_CANCELLED, ITERATE_CANCELLED_MESSAGE,
                    now);
        }
        return settleIfCancelling(runId, now) ? Failure.SETTLED : Failure.FAILED;
    }

    /**
     * The completed iterate step keeps its shape and takes item {@code childId}'s new result and files; a
     * result that cannot be merged is logged and left out, since the item has completed either way.
     */
    private void regather(UUID runId, UUID parentId, RunStepRepository.StepResult parent, UUID childId) {
        IterateProgress.ChildState child = runStepRepository.findChildStates(parentId, parent.getAttempt()).stream()
                .filter(row -> row.getId().equals(childId))
                .map(IterateProgress.ChildState::of)
                .findFirst()
                .orElse(null);
        if (child == null) {
            return;
        }
        try {
            IterateProgress.Complete regathered = IterateProgress.regather(parent.getOutput(), parent.getOutputs(),
                    child);
            runStepRepository.replaceCompletedOutput(parentId, regathered.output(), regathered.outputs());
        } catch (IllegalArgumentException e) {
            LOG.error("Could not regather iterate step {} of run {} after item {} completed", parentId, runId,
                    child.itemIndex(), e);
        }
    }

    private static boolean isItem(Optional<RunStepRepository.Lineage> lineage) {
        return lineage.map(l -> l.getParentStepId() != null).orElse(false);
    }

    /** Expects the caller to hold the run's lock ({@link WorkflowRunRepository#lockById}). */
    private void completeRunIfAllStepsCompleted(UUID runId, Instant now) {
        if (settleIfCancelling(runId, now)) {
            return;
        }
        if (runStepRepository.countTopLevelNotCompleted(runId) > 0) {
            return;
        }
        WorkflowRun run = requireRun(runId);
        if (run.getStatus() == RunStatus.RUNNING) {
            run.markCompleted(now);
            workflowRunRepository.save(run);
            events.publishEvent(new WorkflowRunCompleted(runId, run.getOwnerId()));
            runUpdates.touched(runId);
            childRuns.finished(runId);
            LOG.info("Workflow run finished: runId={} status=completed", runId);
        }
    }

    /** A run being stopped ends {@code cancelled} once its in-flight step has closed; the step keeps its result. */
    private boolean settleIfCancelling(UUID runId, Instant now) {
        if (workflowRunRepository.settleCancelled(runId, now) != 1) {
            return false;
        }
        runUpdates.touched(runId);
        childRuns.finished(runId);
        LOG.info("Workflow run finished: runId={} status=cancelled", runId);
        return true;
    }

    /** Charges the run's owner for a step just claimed; {@code false} when they cannot pay for it. */
    private boolean charge(UUID runId, UUID stepId, short attempt) {
        if (!creditAccounts.enabled()) {
            return true;
        }
        String type = runStepRepository.findById(stepId).map(RunStep::getType)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.runStep.notFound", stepId)));
        return creditAccounts.debitForStep(requireRun(runId).getOwnerId(), runId, stepId, attempt, type);
    }

    private WorkflowRun requireRun(UUID runId) {
        return workflowRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
    }
}
