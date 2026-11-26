package com.flowcortex.workflow.application;

import java.time.Instant;
import java.util.List;

/**
 * A parsed workflow artifact: the name, the ordered steps and the data-store entries (empty when the file has no
 * {@code dataStore} section), nothing environment-bound.
 */
public record WorkflowArtifact(int formatVersion, Instant exportedAt, String name, List<StepDefinition> steps,
        List<WorkflowDataStore.Seed> dataStore) {
}
