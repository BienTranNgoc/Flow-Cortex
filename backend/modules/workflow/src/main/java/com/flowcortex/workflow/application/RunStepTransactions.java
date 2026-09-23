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
 * The three short REQUIRES_NEW transactions a step's dispatch splits across: claim, step work,
 * then complete/fail. Each method must be called through this bean's Spring proxy (never {@code
 * this.method()} from {@link WorkflowRunOrchestrator} — that skips the proxy and runs in the
 * caller's transaction, defeating REQUIRES_NEW), so a mid-run failure persists instead of
 * rolling back with the run's own transaction.
 *
 * <p>Note: step work ({@code StepDispatcher.execute}) currently runs inside the REQUIRES_NEW
 * transaction — harmless for the sub-millisecond demo steps. A real provider-backed step should
 * move that call out of the transaction so it doesn't hold a DB connection for the whole run.
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
