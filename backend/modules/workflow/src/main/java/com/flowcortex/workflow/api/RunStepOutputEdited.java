package com.flowcortex.workflow.api;

import java.util.UUID;

/**
 * The member edited, undid or discarded the edit of a finished step's text, so what the run's results read changed.
 * Published in the transaction that changed it and heard after commit.
 */
public record RunStepOutputEdited(UUID runId, UUID ownerId) {
}
