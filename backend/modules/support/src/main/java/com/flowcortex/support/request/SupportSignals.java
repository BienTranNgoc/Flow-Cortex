package com.flowcortex.support.request;

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
 * Turns a support request change into a {@code support.request.changed} signal on the requester's user topic and on
 * the request's staff topic, once per request after the transaction commits (at once outside one). A failing
 * publish is logged and never reaches the writer: the client's next refetch repairs it.
 */
@Component
class SupportSignals {

    static final String EVENT_TYPE = "support.request.changed";
    static final String REQUEST_TOPIC_PREFIX = "support:request:";

    private static final Logger LOG = LoggerFactory.getLogger(SupportSignals.class);

    private final RealtimeEvents realtimeEvents;

    SupportSignals(RealtimeEvents realtimeEvents) {
        this.realtimeEvents = realtimeEvents;
    }

    void changed(UUID requesterId, long number) {
        Changed changed = new Changed(requesterId, number);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(changed);
            return;
        }
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingChanges pending && pending.owner == this) {
                pending.changes.add(changed);
                return;
            }
        }
        PendingChanges pending = new PendingChanges(this);
        pending.changes.add(changed);
        TransactionSynchronizationManager.registerSynchronization(pending);
    }

    private void publish(Changed changed) {
        publishTo("user:" + changed.requesterId(), changed);
        publishTo(REQUEST_TOPIC_PREFIX + changed.number(), changed);
    }

    private void publishTo(String topic, Changed changed) {
        try {
            realtimeEvents.publish(topic, EVENT_TYPE, Map.of("number", changed.number()));
        } catch (RuntimeException e) {
            LOG.warn("Could not publish {} for request {} on {}", EVENT_TYPE, changed.number(), topic, e);
        }
    }

    private record Changed(UUID requesterId, long number) {
    }

    /** The requests changed in one transaction, signalled in the order they were first changed. */
    private static final class PendingChanges implements TransactionSynchronization {

        private final SupportSignals owner;
        private final Set<Changed> changes = new LinkedHashSet<>();

        private PendingChanges(SupportSignals owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            changes.forEach(owner::publish);
        }
    }
}
