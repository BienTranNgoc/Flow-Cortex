package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
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
    private final MediaJobs mediaJobs;
    private final Clock clock;
    private final RunUpdates runUpdates;
    private final boolean enabled;

    RunCancellation(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            StepExecutorRegistry registry, MediaJobs mediaJobs, Clock clock, RunUpdates runUpdates,
            @Value("${flowcortex.workflow.cancel.enabled:false}") boolean enabled) {
        this.runUpdates = runUpdates;
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.registry = registry;
        this.mediaJobs = mediaJobs;
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
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("feature.turnedOff"));
        }
        stop(currentUserId, runId, "member", currentUserId);
        return currentView(currentUserId, runId);
    }

    /**
     * Stops any member's run as staff, with the same effect as the member's own stop but without the
     * member switch; the answer is read by the caller, since a {@link RunView} carries member content.
     * @throws NotFoundException if the run doesn't exist
     * @throws ConflictException if the run is {@code completed} or {@code error}
     */
    @Transactional
    public void cancelRunAsAdmin(UUID adminId, UUID runId) {
        WorkflowRun run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
        stop(run.getOwnerId(), runId, "admin", adminId);
    }

    private void stop(UUID ownerId, UUID runId, String actor, UUID actorId) {
        if (workflowRunRepository.requestCancel(runId, ownerId) == 1) {
            int skipped = runStepRepository.skipPending(runId);
            boolean settled = workflowRunRepository.settleCancelled(runId, Instant.now(clock)) == 1;
            runUpdates.touched(runId);
            LOG.info("Run cancel: outcome=cancel_requested runId={} actor={} actorId={} skippedSteps={} status={}",
                    runId, actor, actorId, skipped, settled ? "cancelled" : "cancelling");
            return;
        }
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, ownerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
        if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.ERROR) {
            LOG.info("Run cancel: outcome=already_finished runId={} actor={} actorId={} status={}", runId, actor,
                    actorId, run.getStatus());
            throw new ConflictException(new LocalizedMessage("workflow.run.alreadyFinished"));
        }
        LOG.info("Run cancel: outcome=already_stopping runId={} actor={} actorId={} status={}", runId, actor,
                actorId, run.getStatus());
    }

    private RunView currentView(UUID currentUserId, UUID runId) {
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, currentUserId).orElseThrow();
        return WorkflowMapper.toRunView(run, runStepRepository.findByRunIdOrderByStepOrderAsc(runId),
                registry::isRemote, mediaJobs::isDownloadable);
    }
}
