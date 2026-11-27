package com.flowcortex.drive.memory;

import com.flowcortex.workflow.api.RunStepOutputEdited;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Re-projects a run's Memory after the member edits, undoes or discards the text of one of its steps. A failure is
 * not caught, so the persistent event stays incomplete and is delivered again.
 */
@Component
class MemoryEditListener {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryEditListener.class);

    private final MemoryPopulation population;
    private final NamedParameterJdbcTemplate jdbc;

    MemoryEditListener(MemoryPopulation population, NamedParameterJdbcTemplate jdbc) {
        this.population = population;
        this.jdbc = jdbc;
    }

    /**
     * {@code populate} reads the run and then upserts with no run-level lock, so two events of one run heard
     * together could leave the older text in the row for good. The transaction-scoped lock makes each read
     * happen after the previous projection committed.
     */
    @ApplicationModuleListener
    void on(RunStepOutputEdited event) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(:run))",
                new MapSqlParameterSource("run", event.runId().toString()));
        if (population.populate(event.runId()).isEmpty()) {
            LOG.debug("Drive memory: outcome=skipped reason=run-not-completed runId={}", event.runId());
        }
    }
}
