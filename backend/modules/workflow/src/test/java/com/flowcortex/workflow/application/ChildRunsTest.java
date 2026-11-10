package com.flowcortex.workflow.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository.ChildRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

class ChildRunsTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final RunStepRepository steps = mock(RunStepRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RunUpdates runUpdates = mock(RunUpdates.class);
    private final ChildRuns childRuns = new ChildRuns(runs, steps, events, runUpdates, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID parentId = UUID.randomUUID();
    private final UUID childId = UUID.randomUUID();
    private final UUID childOwner = UUID.randomUUID();

    @Test
    void finishedPublishesTheSignalOnlyForARunStartedByAStep() {
        when(runs.findParentStepIdById(childId)).thenReturn(Optional.of(UUID.randomUUID()));
        when(runs.findParentStepIdById(parentId)).thenReturn(Optional.empty());

        childRuns.finished(childId);
        childRuns.finished(parentId);

        verify(events).publishEvent(new ChildRunFinishedEvent(childId));
        verify(events, never()).publishEvent(new ChildRunFinishedEvent(parentId));
    }

    @Test
    void stopChildrenOfStopsEachRunningChildUnderItsOwnOwner() {
        when(runs.findRunningChildren(parentId)).thenReturn(List.of(new ChildRef(childId, childOwner)));
        when(runs.requestCancel(childId, childOwner)).thenReturn(1);
        when(runs.settleCancelled(childId, NOW)).thenReturn(1);
        when(runs.findParentStepIdById(childId)).thenReturn(Optional.of(UUID.randomUUID()));

        childRuns.stopChildrenOf(parentId);

        InOrder order = inOrder(runs, steps, events);
        order.verify(runs).requestCancel(childId, childOwner);
        order.verify(steps).skipPending(childId);
        order.verify(runs).settleCancelled(childId, NOW);
        order.verify(events).publishEvent(new ChildRunFinishedEvent(childId));
        verify(runUpdates).touched(childId);
    }

    @Test
    void aChildThatCannotSettleYetSignalsNothingUntilItsStepCloses() {
        when(runs.findRunningChildren(parentId)).thenReturn(List.of(new ChildRef(childId, childOwner)));
        when(runs.requestCancel(childId, childOwner)).thenReturn(1);
        when(runs.settleCancelled(childId, NOW)).thenReturn(0);

        childRuns.stopChildrenOf(parentId);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void aChildAlreadyStoppingIsNotCancelledAgain() {
        when(runs.findRunningChildren(parentId)).thenReturn(List.of(new ChildRef(childId, childOwner)));
        when(runs.requestCancel(childId, childOwner)).thenReturn(0);

        childRuns.stopChildrenOf(parentId);

        verify(steps, never()).skipPending(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void stopChildrenOfRecursesIntoTheChildrensChildren() {
        UUID grandchildId = UUID.randomUUID();
        UUID grandchildOwner = UUID.randomUUID();
        when(runs.findRunningChildren(parentId)).thenReturn(List.of(new ChildRef(childId, childOwner)));
        when(runs.findRunningChildren(childId)).thenReturn(List.of(new ChildRef(grandchildId, grandchildOwner)));
        when(runs.requestCancel(childId, childOwner)).thenReturn(1);
        when(runs.requestCancel(grandchildId, grandchildOwner)).thenReturn(1);

        childRuns.stopChildrenOf(parentId);

        verify(runs).requestCancel(grandchildId, grandchildOwner);
        verify(steps).skipPending(grandchildId);
    }

    @Test
    void stopChildOfStepStopsTheChildOfThatStepOnly() {
        UUID stepId = UUID.randomUUID();
        when(runs.findRunningChildrenOfStep(stepId)).thenReturn(List.of(new ChildRef(childId, childOwner)));
        when(runs.requestCancel(childId, childOwner)).thenReturn(1);

        childRuns.stopChildOfStep(stepId);

        verify(runs).requestCancel(childId, childOwner);
        verify(steps).skipPending(childId);
        verify(runs, never()).findRunningChildren(parentId);
    }

    @Test
    void aStopByACallerOtherThanTheOwnerStillStopsTheChild() {
        UUID admin = UUID.randomUUID();
        when(runs.findRunningChildren(parentId)).thenReturn(List.of(new ChildRef(childId, childOwner)));
        when(runs.requestCancel(childId, childOwner)).thenReturn(1);

        childRuns.stopChildrenOf(parentId);

        verify(runs, never()).requestCancel(childId, admin);
        verify(runs).requestCancel(childId, childOwner);
    }
}
