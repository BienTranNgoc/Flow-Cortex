package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.ScheduleFire;
import java.time.Instant;
import java.util.UUID;

/**
 * A fire with the run that carries its request key, read in the same statement. {@code runId} is
 * {@code null} when no run has the key; a run the member deleted is still joined, with {@code runDeletedAt}
 * set, so the caller can report that the run happened without exposing it.
 */
public record ScheduleFireRow(ScheduleFire fire, UUID runId, RunStatus runStatus, Instant runDeletedAt) {
}
