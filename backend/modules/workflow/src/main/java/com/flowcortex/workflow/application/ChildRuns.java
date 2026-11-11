package com.flowcortex.workflow.application;

import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository.ChildRef;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The two directions between a run and the runs its {@code workflow.call} steps started: a child's
 * end is signalled up to the waiting step, and a parent's stop is passed down. Both run inside the
 * caller's transaction. Locks are taken parent before child only; a child reaches its parent
 * through the asynchronous {@link ChildRunFinishedEvent}, so the order cannot deadlock.
 */
@Component
class ChildRuns {

    private static final Logger LOG = LoggerFactory.getLogger(ChildRuns.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final ApplicationEventPublisher events;
    private final RunUpdates runUpdates;
    private final Clock clock;

    ChildRuns(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            ApplicationEventPublisher events, RunUpdates runUpdates, Clock clock) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.events = events;
        this.runUpdates = runUpdates;
        this.clock = clock;
    }

    /** Call wherever a run's status becomes final; a no-op for a run no step started. */
    void finished(UUID runId) {
        if (workflowRunRepository.findParentStepIdById(runId).isPresent()) {
            events.publishEvent(new ChildRunFinishedEvent(runId));
        }
    }

    /** Stops every unfinished child of {@code parentRunId}, and theirs in turn. */
    void stopChildrenOf(UUID parentRunId) {
        stopAll(workflowRunRepository.findRunningChildren(parentRunId));
    }

    /** Stops the unfinished child started by one step, and its descendants. */
    void stopChildOfStep(UUID parentStepId) {
        stopAll(workflowRunRepository.findRunningChildrenOfStep(parentStepId));
    }

    private void stopAll(List<ChildRef> children) {
        Instant now = clock.instant();
        for (ChildRef child : children) {
            stop(child, now);
        }
    }

    /** Issued under the child row's own owner: the stop's caller may be an admin or the parent's owner. */
    private void stop(ChildRef child, Instant now) {
        if (workflowRunRepository.requestCancel(child.id(), child.ownerId()) == 1) {
            int skipped = runStepRepository.skipPending(child.id());
            boolean settled = workflowRunRepository.settleCancelled(child.id(), now) == 1;
            runUpdates.touched(child.id());
            if (settled) {
                finished(child.id());
            }
            LOG.info("Child run stop: runId={} skippedSteps={} status={}", child.id(), skipped,
                    settled ? "cancelled" : "cancelling");
        }
        stopChildrenOf(child.id());
    }
}
