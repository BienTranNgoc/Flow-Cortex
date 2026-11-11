package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.domain.Workflow;
import java.time.Instant;

/** A saved workflow with the time it was saved, read in the same statement. */
public record BookmarkedWorkflowRow(Workflow workflow, Instant bookmarkedAt) {
}
