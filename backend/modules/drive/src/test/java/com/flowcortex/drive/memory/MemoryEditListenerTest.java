package com.flowcortex.drive.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.workflow.api.RunStepOutputEdited;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/** An edit of a finished step re-projects its run, after taking the run's lock. */
class MemoryEditListenerTest {

    private final MemoryPopulation population = mock(MemoryPopulation.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final MemoryEditListener listener = new MemoryEditListener(population, jdbc);

    private final UUID runId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @Test
    void theEditEventPopulatesItsRunOnceAfterTheRunLockIsTaken() {
        when(population.populate(runId)).thenReturn(Optional.of(new MemoryPopulation.Outcome(UUID.randomUUID(),
                ownerId, 0, 1, 0)));

        listener.on(new RunStepOutputEdited(runId, ownerId));

        InOrder order = inOrder(jdbc, population);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        order.verify(jdbc).queryForList(contains("pg_advisory_xact_lock(hashtext("), params.capture());
        order.verify(population).populate(runId);
        verify(population, times(1)).populate(runId);
        assertThat(params.getValue().getValue("run")).isEqualTo(runId.toString());
    }

    @Test
    void anEmptyOutcomeIsNotAnError() {
        when(population.populate(runId)).thenReturn(Optional.empty());

        listener.on(new RunStepOutputEdited(runId, ownerId));

        verify(population).populate(runId);
    }

    @Test
    void aFailureOfThePopulationIsNotCaught() {
        when(population.populate(runId)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> listener.on(new RunStepOutputEdited(runId, ownerId)))
                .isInstanceOf(IllegalStateException.class);
    }
}
