package com.flowcortex.workflow.api;

import java.util.Optional;
import java.util.UUID;

/** Read port over a run: its owner, the text its title derives from, and its steps. */
public interface RunRecords {

    /** The run, or empty when no run has this id. */
    Optional<RunRecord> find(UUID runId);
}
