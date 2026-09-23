package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three short REQUIRES_NEW transactions a single step's dispatch is
 * split across: claim, then the step's own work runs, then complete/fail.
 * Each method here is its OWN transaction, called through this bean's Spring
 * proxy (never {@code this.method()} from within {@link
 * WorkflowRunOrchestrator} — that would silently skip the proxy and run in
 * the caller's own transaction, defeating REQUIRES_NEW), so a mid-run step
 * failure PERSISTS instead of rolling back with whatever transaction started
 * the run.
 *
 * <p>Note: the step work itself ({@code StepDispatcher.execute}) currently
 * runs inside the listener's REQUIRES_NEW transaction, which is harmless for
 * the sub-millisecond {@code echo}/{@code fail} demo steps. When a real step
 * type that calls a slow external provider is added, move that call OUT of a
 * transaction so it does not hold a DB connection for the whole run.
 */
@Component
class RunStepTransactions {

    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;

    RunStepTransactions(RunStepRepository runStepRepository, WorkflowRunRepository workflowRunRepository) {
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
    }

    /** {@code pending -> running}, atomically — see {@code RunStepRepository#claimPending}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean claim(UUID stepId, Instant now) {
        return runStepRepository.claimPending(stepId, now) == 1;
    }

    /** {@code running -> completed}; marks the owning run {@code completed} too if this was the last step. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(UUID runId, UUID stepId, String resolvedInput, String output, Instant now) {
        RunStep step = requireStep(stepId);
        step.markCompleted(resolvedInput, output, now);
        runStepRepository.save(step);

        boolean anyStepStillOpen = runStepRepository.countByRunIdAndStatusNot(runId, RunStepStatus.COMPLETED) > 0;
        if (!anyStepStillOpen) {
            WorkflowRun run = requireRun(runId);
            run.markCompleted(now);
            workflowRunRepository.save(run);
        }
    }

    /** {@code running -> error}; marks the owning run {@code error} unconditionally — halts further dispatch. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(UUID runId, UUID stepId, String resolvedInput, String errorMessage, Instant now) {
        RunStep step = requireStep(stepId);
        step.markError(resolvedInput, errorMessage, now);
        runStepRepository.save(step);

        WorkflowRun run = requireRun(runId);
        run.markError(errorMessage, now);
        workflowRunRepository.save(run);
    }

    private RunStep requireStep(UUID stepId) {
        return runStepRepository.findById(stepId)
                .orElseThrow(() -> new NotFoundException("Run step not found: " + stepId));
    }

    private WorkflowRun requireRun(UUID runId) {
        return workflowRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
    }
}
