package com.flowcortex.drive.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.drive.domain.BackfillReconciler;
import com.flowcortex.drive.domain.BackfillReconciler.Key;
import com.flowcortex.drive.domain.BackfillReconciler.SpaceKeys;
import com.flowcortex.drive.domain.MemoryProjector;
import com.flowcortex.drive.memory.MemoryPopulation;
import com.flowcortex.drive.memory.MemoryResultRepository;
import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResultView.Step;
import com.flowcortex.workflow.api.RunResults;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** What each mode writes, what it only reads, and the exit code it ends with. */
class DriveBackfillTest {

    private static final Instant FINISHED = Instant.parse("2026-11-01T10:05:00Z");

    private final RunResults runResults = mock(RunResults.class);
    private final MemoryPopulation population = mock(MemoryPopulation.class);
    private final PersonalSpaces personalSpaces = mock(PersonalSpaces.class);
    private final MemoryResultRepository memory = mock(MemoryResultRepository.class);
    private final DriveBackfillRepository backfills = mock(DriveBackfillRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final DriveBackfill backfill = new DriveBackfill(runResults, population, personalSpaces, memory,
            backfills, transactionManager);

    private final UUID ownerId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID firstRun = UUID.randomUUID();
    private final UUID secondRun = UUID.randomUUID();

    @BeforeEach
    void transactionsAndSpaces() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(personalSpaces.personalSpaceOf(ownerId)).thenReturn(spaceId);
    }

    private void completedRuns(UUID... runIds) {
        when(runResults.completedRunIds(isNull(), eq(DriveBackfill.BATCH_SIZE))).thenReturn(List.of(runIds));
        when(runResults.completedRunIds(eq(runIds[runIds.length - 1]), eq(DriveBackfill.BATCH_SIZE)))
                .thenReturn(List.of());
    }

    private RunResultView textRun(UUID runId) {
        Step echo = new Step(UUID.randomUUID(), 1, "echo", "inline", "completed", "a result", FINISHED, List.of());
        RunResultView view = new RunResultView(runId, ownerId, "Notes", "input", FINISHED, List.of(echo));
        when(runResults.completedRun(runId)).thenReturn(Optional.of(view));
        return view;
    }

    private Set<Key> keysOf(RunResultView view) {
        Set<Key> keys = new HashSet<>();
        MemoryProjector.project(view).forEach(entry -> keys.add(new Key(view.runId(), entry.resultKey())));
        return keys;
    }

    @Test
    void anUnknownModeReadsAndWritesNothing() {
        assertThat(backfill.run("rewrite", false)).isEqualTo(BackfillReconciler.EXIT_UNKNOWN_MODE);

        verifyNoInteractions(runResults, population, memory, backfills);
    }

    @Test
    void applyPopulatesEveryRunAndRecordsOnlyRunsThatInsertedSomething() {
        completedRuns(firstRun, secondRun);
        when(population.populate(firstRun))
                .thenReturn(Optional.of(new MemoryPopulation.Outcome(spaceId, ownerId, 2, 0, 0)));
        when(population.populate(secondRun))
                .thenReturn(Optional.of(new MemoryPopulation.Outcome(spaceId, ownerId, 0, 1, 0)));

        assertThat(backfill.run("apply", false)).isEqualTo(BackfillReconciler.EXIT_OK);

        verify(backfills).recordApply(spaceId, ownerId, 1, 2);
        verify(backfills, never()).recordApply(any(), any(), anyInt(), eq(0));
    }

    @Test
    void applySkipsAFailingRunKeepsGoingAndExitsWithAFailure() {
        completedRuns(firstRun, secondRun);
        when(population.populate(firstRun)).thenThrow(new IllegalStateException("broken run"));
        when(population.populate(secondRun))
                .thenReturn(Optional.of(new MemoryPopulation.Outcome(spaceId, ownerId, 1, 0, 0)));

        assertThat(backfill.run("apply", false)).isEqualTo(BackfillReconciler.EXIT_DATA_ACCESS_FAILURE);

        verify(backfills).recordApply(spaceId, ownerId, 1, 1);
    }

    @Test
    void dryRunReadsMemoryButWritesNothing() {
        completedRuns(firstRun);
        textRun(firstRun);
        when(memory.keysBySpace(List.of(firstRun))).thenReturn(Map.of());

        assertThat(backfill.run("dry-run", false)).isEqualTo(BackfillReconciler.EXIT_OK);

        verifyNoInteractions(population, backfills);
    }

    @Test
    void reconcileSavesADifferingSpaceAsNotOkAndExitsWithAMismatch() {
        completedRuns(firstRun);
        textRun(firstRun);
        when(memory.keysBySpace(List.of(firstRun))).thenReturn(Map.of());

        assertThat(backfill.run("reconcile", true)).isEqualTo(BackfillReconciler.EXIT_MISMATCH);

        verify(backfills).saveReconcile(spaceId, ownerId, false);
    }

    @Test
    void reconcileSavesAMatchingSpaceAsOk() {
        completedRuns(firstRun);
        RunResultView view = textRun(firstRun);
        when(memory.keysBySpace(List.of(firstRun)))
                .thenReturn(Map.of(spaceId, new SpaceKeys(ownerId, keysOf(view))));

        assertThat(backfill.run("reconcile", true)).isEqualTo(BackfillReconciler.EXIT_OK);

        verify(backfills).saveReconcile(spaceId, ownerId, true);
    }

    @Test
    void reconcileWithoutSaveRecordsNothing() {
        completedRuns(firstRun);
        textRun(firstRun);
        when(memory.keysBySpace(List.of(firstRun))).thenReturn(Map.of());

        assertThat(backfill.run("reconcile", false)).isEqualTo(BackfillReconciler.EXIT_MISMATCH);

        verify(backfills, never()).saveReconcile(any(), any(), anyBoolean());
    }

    @Test
    void aRunThatCannotBeProjectedIsSkippedAndTheRestStillCompared() {
        completedRuns(firstRun, secondRun);
        when(runResults.completedRun(firstRun)).thenThrow(new IllegalStateException("unreadable"));
        RunResultView view = textRun(secondRun);
        when(memory.keysBySpace(List.of(secondRun)))
                .thenReturn(Map.of(spaceId, new SpaceKeys(ownerId, keysOf(view))));

        assertThat(backfill.run("reconcile", true)).isEqualTo(BackfillReconciler.EXIT_DATA_ACCESS_FAILURE);

        verify(backfills).saveReconcile(spaceId, ownerId, true);
    }

    @Test
    void aDatabaseThatCannotBeReadEndsWithAFailureCode() {
        when(runResults.completedRunIds(isNull(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(backfill.run("dry-run", false)).isEqualTo(BackfillReconciler.EXIT_DATA_ACCESS_FAILURE);

        verifyNoInteractions(backfills);
    }
}
