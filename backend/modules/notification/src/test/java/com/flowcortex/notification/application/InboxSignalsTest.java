package com.flowcortex.notification.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.flowcortex.realtime.api.RealtimeEvents;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class InboxSignalsTest {

    private final RealtimeEvents realtime = mock(RealtimeEvents.class);
    private final InboxSignals signals = new InboxSignals(realtime);
    private final UUID member = UUID.randomUUID();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesOnceAfterCommitToTheMembersUserTopic() {
        TransactionSynchronizationManager.initSynchronization();

        signals.changed(member);
        signals.changed(member);
        verify(realtime, never()).publish(anyString(), anyString(), any());
        commit();

        verify(realtime, times(1)).publish("user:" + member, "notifications.changed", Map.of());
    }

    @Test
    void publishesNothingOnRollback() {
        TransactionSynchronizationManager.initSynchronization();

        signals.changed(member);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(realtime, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void aFailingPublishIsLoggedAndSwallowed() {
        doThrow(new IllegalStateException("broker down")).when(realtime).publish(anyString(), anyString(), any());
        TransactionSynchronizationManager.initSynchronization();

        signals.changed(member);
        commit();

        verify(realtime).publish("user:" + member, "notifications.changed", Map.of());
    }

    @Test
    void outsideATransactionPublishesAtOnce() {
        signals.changed(member);

        verify(realtime).publish("user:" + member, "notifications.changed", Map.of());
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }
}
