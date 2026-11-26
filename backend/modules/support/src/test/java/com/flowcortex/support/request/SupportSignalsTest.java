package com.flowcortex.support.request;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

class SupportSignalsTest {

    private final RealtimeEvents realtime = mock(RealtimeEvents.class);
    private final SupportSignals signals = new SupportSignals(realtime);
    private final UUID requester = UUID.randomUUID();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesOnceToBothTopicsAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        signals.changed(requester, 42);
        signals.changed(requester, 42);
        verify(realtime, never()).publish(anyString(), anyString(), any());
        commit();

        verify(realtime, times(1)).publish("user:" + requester, "support.request.changed", Map.of("number", 42L));
        verify(realtime, times(1)).publish("support:request:42", "support.request.changed", Map.of("number", 42L));
    }

    @Test
    void twoRequestsInOneTransactionAreEachSignalledOnce() {
        UUID other = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();

        signals.changed(requester, 42);
        signals.changed(other, 43);
        signals.changed(requester, 42);
        commit();

        verify(realtime, times(1)).publish("support:request:42", "support.request.changed", Map.of("number", 42L));
        verify(realtime, times(1)).publish("support:request:43", "support.request.changed", Map.of("number", 43L));
        verify(realtime, times(1)).publish("user:" + other, "support.request.changed", Map.of("number", 43L));
    }

    @Test
    void publishesNothingOnRollback() {
        TransactionSynchronizationManager.initSynchronization();

        signals.changed(requester, 42);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(realtime, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void aFailingPublishIsLoggedAndSwallowed() {
        doThrow(new IllegalStateException("broker down")).when(realtime).publish(eq("user:" + requester), anyString(), any());
        TransactionSynchronizationManager.initSynchronization();

        signals.changed(requester, 42);
        commit();

        verify(realtime).publish("user:" + requester, "support.request.changed", Map.of("number", 42L));
        verify(realtime).publish("support:request:42", "support.request.changed", Map.of("number", 42L));
    }

    @Test
    void outsideATransactionPublishesAtOnce() {
        signals.changed(requester, 42);

        verify(realtime).publish("user:" + requester, "support.request.changed", Map.of("number", 42L));
        verify(realtime).publish("support:request:42", "support.request.changed", Map.of("number", 42L));
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }
}
