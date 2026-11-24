package com.flowcortex.workflow.application;

import java.util.UUID;

/** A QA review task was created waiting for its questions to be written. */
public record QaReviewOpenedEvent(UUID taskId) {
}
