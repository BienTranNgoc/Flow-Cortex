package com.flowcortex.notification.application;

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
 * Turns an inbox change into a {@code notifications.changed} signal on the member's user topic,
 * once per member after the transaction commits (at once outside one). A failing publish is logged
 * and never reaches the writer: the client's next refetch repairs it.
 */
@Component
class InboxSignals {

    static final String EVENT_TYPE = "notifications.changed";

    private static final Logger LOG = LoggerFactory.getLogger(InboxSignals.class);

    private final RealtimeEvents realtimeEvents;

    InboxSignals(RealtimeEvents realtimeEvents) {
        this.realtimeEvents = realtimeEvents;
    }

    void changed(UUID memberId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(memberId);
            return;
        }
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingMembers pending && pending.owner == this) {
                pending.memberIds.add(memberId);
                return;
            }
        }
        PendingMembers pending = new PendingMembers(this);
        pending.memberIds.add(memberId);
        TransactionSynchronizationManager.registerSynchronization(pending);
    }

    private void publish(UUID memberId) {
        try {
            realtimeEvents.publish("user:" + memberId, EVENT_TYPE, Map.of());
        } catch (RuntimeException e) {
            LOG.warn("Could not publish {} for member {}", EVENT_TYPE, memberId, e);
        }
    }

    /** The members changed in one transaction, signalled in the order they were first changed. */
    private static final class PendingMembers implements TransactionSynchronization {

        private final InboxSignals owner;
        private final Set<UUID> memberIds = new LinkedHashSet<>();

        private PendingMembers(InboxSignals owner) {
            this.owner = owner;
        }

        @Override
        public void afterCommit() {
            memberIds.forEach(owner::publish);
        }
    }
}
