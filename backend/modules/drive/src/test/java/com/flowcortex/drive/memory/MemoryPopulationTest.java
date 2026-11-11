package com.flowcortex.drive.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResultView.Step;
import com.flowcortex.workflow.api.RunResults;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A completed run reaches the owner's personal space; anything else writes nothing. */
class MemoryPopulationTest {

    private static final Instant FINISHED = Instant.parse("2026-11-01T10:05:00Z");

    private final RunResults runResults = mock(RunResults.class);
    private final PersonalSpaces personalSpaces = mock(PersonalSpaces.class);
    private final MemoryResultRepository memory = mock(MemoryResultRepository.class);
    private final MemoryPopulation population = new MemoryPopulation(runResults, personalSpaces, memory);

    private final UUID runId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();

    @Test
    void aCompletedRunIsWrittenToTheOwnersPersonalSpace() {
        Step echo = new Step(UUID.randomUUID(), 1, "echo", "inline", "completed", "a result", FINISHED, List.of());
        when(runResults.completedRun(runId))
                .thenReturn(Optional.of(new RunResultView(runId, ownerId, "Notes", "in", FINISHED, List.of(echo))));
        when(personalSpaces.personalSpaceOf(ownerId)).thenReturn(spaceId);
        when(memory.replaceRun(eq(spaceId), eq(ownerId), eq(runId), anyList()))
                .thenReturn(new MemoryResultRepository.Replaced(1, 0, 0));

        assertThat(population.populate(runId))
                .contains(new MemoryPopulation.Outcome(spaceId, ownerId, 1, 0, 0));
    }

    @Test
    void aRunThatIsNotAResultRunWritesNothing() {
        when(runResults.completedRun(runId)).thenReturn(Optional.empty());

        assertThat(population.populate(runId)).isEmpty();

        verifyNoInteractions(personalSpaces, memory);
    }

    @Test
    void theCompletionEventPopulatesItsRun() {
        when(runResults.completedRun(runId)).thenReturn(Optional.empty());

        population.on(new WorkflowRunCompleted(runId, ownerId));

        verify(runResults).completedRun(runId);
    }
}
