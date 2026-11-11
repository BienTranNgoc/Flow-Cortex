package com.flowcortex.drive.memory;

import com.flowcortex.drive.domain.MemoryProjector;
import com.flowcortex.drive.domain.MemoryProjector.Entry;
import com.flowcortex.space.api.PersonalSpaces;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResults;
import com.flowcortex.workflow.api.WorkflowRunCompleted;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps each owner's Memory in step with their completed runs, in the owner's personal space whatever the
 * spaces switch says. A failure is not caught, so the persistent event stays incomplete and is delivered again.
 */
@Component
public class MemoryPopulation {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryPopulation.class);

    /** What one population wrote, and where. */
    public record Outcome(UUID spaceId, UUID ownerId, int inserted, int updated, int removed) {
    }

    private final RunResults runResults;
    private final PersonalSpaces personalSpaces;
    private final MemoryResultRepository memory;

    MemoryPopulation(RunResults runResults, PersonalSpaces personalSpaces, MemoryResultRepository memory) {
        this.runResults = runResults;
        this.personalSpaces = personalSpaces;
        this.memory = memory;
    }

    @ApplicationModuleListener
    void on(WorkflowRunCompleted event) {
        populate(event.runId());
    }

    /**
     * Projects the run and makes its Memory rows match, in one transaction; idempotent.
     *
     * @return what changed, or empty when the run is not a result run (nothing is written for it)
     */
    @Transactional
    public Optional<Outcome> populate(UUID runId) {
        Optional<RunResultView> run = runResults.completedRun(runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        UUID ownerId = run.get().ownerId();
        List<Entry> entries = MemoryProjector.project(run.get());
        UUID spaceId = personalSpaces.personalSpaceOf(ownerId);
        MemoryResultRepository.Replaced replaced = memory.replaceRun(spaceId, ownerId, runId, entries);
        LOG.info("Drive memory: outcome=populated runId={} ownerId={} inserted={} updated={} removed={}", runId,
                ownerId, replaced.inserted(), replaced.updated(), replaced.removed());
        return Optional.of(new Outcome(spaceId, ownerId, replaced.inserted(), replaced.updated(), replaced.removed()));
    }
}
