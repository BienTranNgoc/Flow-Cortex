package com.flowcortex.workflow.application;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * admin pages show.
 */
@Component
class RunStepTransactions {

    /** How a claim ended: someone else holds the step, it was handed on, or it failed in the claim. */
    enum ClaimOutcome {
        LOST,
        DISPATCHED,
        FAILED
    }

    static final String NOT_ENOUGH_CREDITS = "Not enough credits to run this step";

    private static final Logger LOG = LoggerFactory.getLogger(RunStepTransactions.class);

    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final CreditAccounts creditAccounts;

    RunStepTransactions(RunStepRepository runStepRepository, WorkflowRunRepository workflowRunRepository,
            CreditAccounts creditAccounts) {
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.creditAccounts = creditAccounts;
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
        if (!charge(runId, stepId)) {
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
    boolean recordPickup(UUID stepId, short attempt, Instant at, Instant leaseUntil, Instant runDeadline,
            short deliveryCount) {
        return runStepRepository.recordPickup(stepId, attempt, at, leaseUntil, runDeadline, deliveryCount) == 1;
    }

    /** A heartbeat; {@code false} if the step is no longer running at this attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean extendLease(UUID stepId, short attempt, Instant now, Instant leaseUntil, Short percent,
            short deliveryCount, Instant runDeadlineIfUnset) {
        return runStepRepository.extendLease(stepId, attempt, now, leaseUntil, percent, deliveryCount,
                runDeadlineIfUnset) == 1;
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
        afterAccepted.run();
        completeRunIfAllStepsCompleted(runId, now);
        return true;
    }

    /**
     * {@code running -> error}; marks the owning run {@code error} — halts further dispatch — or, when the
     * run is being stopped, settles it {@code cancelled} instead.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean fail(UUID runId, UUID stepId, short attempt, String code, String message, Instant now) {
        workflowRunRepository.lockById(runId);
        return failRunning(runId, stepId, attempt, code, message, now);
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
    private boolean failRunning(UUID runId, UUID stepId, short attempt, String code, String message, Instant now) {
        if (runStepRepository.failIfRunning(stepId, runId, attempt, code, message, now) != 1) {
            return false;
        }
        creditAccounts.refundStep(runId, stepId, code);
        if (settleIfCancelling(runId, now)) {
            return true;
        }
        WorkflowRun run = requireRun(runId);
        if (run.markError(message, now)) {
            workflowRunRepository.save(run);
            LOG.info("Workflow run finished: runId={} status=error code={}", runId, code);
        }
        return true;
    }

    /** Expects the caller to hold the run's lock ({@link WorkflowRunRepository#lockById}). */
    private void completeRunIfAllStepsCompleted(UUID runId, Instant now) {
        if (settleIfCancelling(runId, now)) {
            return;
        }
        if (runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED) > 0) {
            return;
        }
        WorkflowRun run = requireRun(runId);
        if (run.getStatus() == RunStatus.RUNNING) {
            run.markCompleted(now);
            workflowRunRepository.save(run);
            LOG.info("Workflow run finished: runId={} status=completed", runId);
        }
    }

    /** A run being stopped ends {@code cancelled} once its in-flight step has closed; the step keeps its result. */
    private boolean settleIfCancelling(UUID runId, Instant now) {
        if (workflowRunRepository.settleCancelled(runId, now) != 1) {
            return false;
        }
        LOG.info("Workflow run finished: runId={} status=cancelled", runId);
        return true;
    }

    /** Charges the run's owner for a step just claimed; {@code false} when they cannot pay for it. */
    private boolean charge(UUID runId, UUID stepId) {
        if (!creditAccounts.enabled()) {
            return true;
        }
        String type = runStepRepository.findById(stepId).map(RunStep::getType)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.runStep.notFound", stepId)));
        return creditAccounts.debitForStep(requireRun(runId).getOwnerId(), runId, stepId, type);
    }

    private WorkflowRun requireRun(UUID runId) {
        return workflowRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
    }
}
