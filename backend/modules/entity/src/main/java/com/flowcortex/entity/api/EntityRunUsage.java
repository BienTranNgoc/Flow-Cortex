package com.flowcortex.entity.api;

import java.util.Collection;
import java.util.UUID;

/**
 * Records which entities a run used, so each entity can list the completed runs that used it. The first caller is
 * the scene-view fill step of the entity-fill flow.
 */
public interface EntityRunUsage {

    /**
     * Records, once per run and entity, the entity's current version and the run's workflow id and name, for every
     * id in {@code entityIds} that is a live entity of the owner's personal space; any other id is ignored. A usage
     * row of a run that is already completed is completed at once; otherwise the run-completed signal completes it.
     *
     * @return the number of usage rows this call wrote (an entity already recorded for the run counts 0)
     * @throws IllegalArgumentException when no run has {@code runId} or the run belongs to another owner; nothing
     *         is recorded then
     */
    int record(UUID runId, UUID ownerId, Collection<UUID> entityIds);
}
