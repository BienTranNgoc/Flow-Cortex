package com.flowcortex.workflow.infrastructure;

import java.util.UUID;

/** How many rows of one batch are in each member-facing status; a {@code starting} row counts as pending. */
public record BatchRowCounts(UUID batchId, long pending, long running, long completed, long failed, long cancelled) {
}
