package com.flowcortex.workflow.application;

import com.flowcortex.realtime.api.RealtimeEvents;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Turns a run write into a {@code run.updated} signal, once per run after the transaction commits
 * (at once outside one). A failing publish is logged and never reaches the writer: the client's
 * next resync repairs it.
 */
@Component
class RunUpdates {

    static final String EVENT_TYPE = "run.updated";

    private static final Logger LOG = LoggerFactory.getLogger(RunUpdates.class);

    private final RealtimeEvents realtimeEvents;

    RunUpdates(RealtimeEvents realtimeEvents) {
        this.realtimeEvents = realtimeEvents;
    }

    void touched(UUID runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(runId);
            return;
        }
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingRuns pending && pending.owner == this) {
                pending.runIds.add(runId);
                return;
            }
        }
        PendingRuns pending = new PendingRuns(this);
        pending.runIds.add(runId);
        TransactionSynchronizationManager.registerSynchronization(pending);
    }

    private void publish(UUID runId) {
        try {
            realtimeEvents.publish("run:" + runId, EVENT_TYPE, Map.of("runId", runId));
        } catch (RuntimeException e) {
            LOG.warn("Could not publish {} for run {}", EVENT_TYPE, runId, e);
        }
    }

    /** The runs touched in one transaction, published in the order they were first touched. */
    private static final class PendingRuns implements TransactionSynchronization {

        private final RunUpdates owner;
        private final Set<UUID> runIds = new LinkedHashSet<>();

        private PendingRuns(RunUpdates owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            runIds.forEach(owner::publish);
        }
    }
}
