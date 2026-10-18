package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A member stopping their own run. The step in flight finishes; every step not started is skipped
 * at once; the run is {@code cancelling} until no step is running, then {@code cancelled}. Each
 * guard is one conditional {@code UPDATE}, so a stop racing a step's claim or result is decided by
 * the database, and the run's row lock taken by the first one orders it against both.
 */
@Service
public class RunCancellation {

    private static final Logger LOG = LoggerFactory.getLogger(RunCancellation.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final StepExecutorRegistry registry;
    private final Clock clock;
    private final boolean enabled;

    RunCancellation(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            StepExecutorRegistry registry, Clock clock,
            @Value("${flowcortex.workflow.cancel.enabled:false}") boolean enabled) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.registry = registry;
        this.clock = clock;
        this.enabled = enabled;
    }

    /**
     * Stops {@code runId} if it is {@code currentUserId}'s and still {@code running} or {@code
     * waiting_approval}; a run already {@code cancelling} or {@code cancelled} is returned unchanged.
     * @throws ResponseStatusException 503 when cancellation is switched off
     * @throws NotFoundException if the run doesn't exist or isn't the caller's
     * @throws ConflictException if the run is {@code completed} or {@code error}
     */
    @Transactional
    public RunView cancelRun(UUID currentUserId, UUID runId) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "This feature is turned off");
        }
        if (workflowRunRepository.requestCancel(runId, currentUserId) == 1) {
            int skipped = runStepRepository.skipPending(runId);
            boolean settled = workflowRunRepository.settleCancelled(runId, Instant.now(clock)) == 1;
            LOG.info("Run cancel: outcome=cancel_requested runId={} skippedSteps={} status={}", runId, skipped,
                    settled ? "cancelled" : "cancelling");
            return currentView(currentUserId, runId);
        }
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, currentUserId)
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.ERROR) {
            LOG.info("Run cancel: outcome=already_finished runId={} status={}", runId, run.getStatus());
            throw new ConflictException("This run has already finished");
        }
        LOG.info("Run cancel: outcome=already_stopping runId={} status={}", runId, run.getStatus());
        return WorkflowMapper.toRunView(run, runStepRepository.findByRunIdOrderByStepOrderAsc(runId),
                registry::isRemote);
    }

    private RunView currentView(UUID currentUserId, UUID runId) {
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, currentUserId).orElseThrow();
        return WorkflowMapper.toRunView(run, runStepRepository.findByRunIdOrderByStepOrderAsc(runId),
                registry::isRemote);
    }
}
