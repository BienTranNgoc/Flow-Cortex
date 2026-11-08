package com.flowcortex.workflow.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.flowcortex.realtime.api.RealtimeEvents;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The after-commit signal, driven through the synchronization manager with no database. */
class RunUpdatesTest {

    private final RealtimeEvents events = mock(RealtimeEvents.class);
    private final RunUpdates updates = new RunUpdates(events);
    private final UUID runId = UUID.randomUUID();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(sync -> sync.beforeCommit(false));
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private void rollBack() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    @Test
    void severalTouchesOfOneRunPublishOnceAfterTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        updates.touched(runId);
        updates.touched(runId);
        updates.touched(runId);
        verifyNoInteractions(events);
        commit();

        verify(events, times(1)).publish("run:" + runId, "run.updated", Map.of("runId", runId));
    }

    @Test
    void twoRunsInOneTransactionPublishTwice() {
        UUID other = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();

        updates.touched(runId);
        updates.touched(other);
        commit();

        verify(events).publish("run:" + runId, "run.updated", Map.of("runId", runId));
        verify(events).publish("run:" + other, "run.updated", Map.of("runId", other));
        verify(events, times(2)).publish(anyString(), anyString(), any());
    }

    @Test
    void aRollbackPublishesNothing() {
        TransactionSynchronizationManager.initSynchronization();

        updates.touched(runId);
        rollBack();

        verify(events, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void withoutATransactionTheEventGoesAtOnce() {
        updates.touched(runId);

        verify(events).publish("run:" + runId, "run.updated", Map.of("runId", runId));
    }

    @Test
    void aThrowingPortDoesNotReachTheCaller() {
        doThrow(new IllegalStateException("broker down")).when(events).publish(anyString(), anyString(), any());

        updates.touched(runId);

        TransactionSynchronizationManager.initSynchronization();
        updates.touched(runId);
        commit();

        verify(events, times(2)).publish(anyString(), anyString(), any());
    }

    @Test
    void aFailingPublishDoesNotStopTheNextRunsSignal() {
        UUID other = UUID.randomUUID();
        doThrow(new IllegalStateException("broker down")).when(events)
                .publish("run:" + runId, "run.updated", Map.of("runId", runId));
        TransactionSynchronizationManager.initSynchronization();

        updates.touched(runId);
        updates.touched(other);
        commit();

        verify(events).publish("run:" + other, "run.updated", Map.of("runId", other));
    }
}
