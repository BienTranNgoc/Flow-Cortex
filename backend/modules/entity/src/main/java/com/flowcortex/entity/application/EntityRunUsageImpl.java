package com.flowcortex.entity.application;

import com.flowcortex.entity.api.EntityRunUsage;
import com.flowcortex.entity.infrastructure.EntityRunStore;
import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.workflow.api.RunRecord;
import com.flowcortex.workflow.api.RunRecords;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records entity usage for a run. A run that is not completed when read may complete before this recording
 * commits, and then the run-completed listener finds no row to stamp and counts itself done; so the recording
 * re-reads the run once its own writes are committed and stamps the rows itself when the run completed meanwhile.
 */
@Service
class EntityRunUsageImpl implements EntityRunUsage {

    private static final String COMPLETED = "completed";

    private final RunRecords runs;
    private final PersonalSpaces personalSpaces;
    private final EntityRunStore store;
    private final Clock clock;

    EntityRunUsageImpl(RunRecords runs, PersonalSpaces personalSpaces, EntityRunStore store, Clock clock) {
        this.runs = runs;
        this.personalSpaces = personalSpaces;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public int record(UUID runId, UUID ownerId, Collection<UUID> entityIds) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(entityIds, "entityIds");
        RunRecord run = runs.find(runId).filter(found -> ownerId.equals(found.ownerId()))
                .orElseThrow(() -> new IllegalArgumentException("No run " + runId + " of owner " + ownerId));
        List<UUID> ids = entityIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        boolean completed = COMPLETED.equals(run.status());
        int recorded = store.insertLiveIfAbsent(runId, personalSpaces.personalSpaceOf(ownerId), ids, run.workflowId(),
                run.workflowName(), completed ? finishTime(run, clock) : null);
        if (!completed) {
            afterCommit(() -> stampIfCompletedNow(runId));
        }
        return recorded;
    }

    /** The run's finish time, or now when the row carries none. */
    static Instant finishTime(RunRecord run, Clock clock) {
        return run.finishedAt() != null ? run.finishedAt() : clock.instant();
    }

    private void stampIfCompletedNow(UUID runId) {
        runs.find(runId).filter(found -> COMPLETED.equals(found.status()))
                .ifPresent(found -> store.markCompleted(runId, finishTime(found, clock)));
    }

    private static void afterCommit(Runnable step) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            step.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                step.run();
            }
        });
    }
}
