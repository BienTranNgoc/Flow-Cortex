package com.flowcortex.workflow.application;

import java.time.Instant;
import java.util.List;

/** A parsed workflow artifact: the name and the ordered steps, nothing environment-bound. */
public record WorkflowArtifact(int formatVersion, Instant exportedAt, String name, List<StepDefinition> steps) {
}
