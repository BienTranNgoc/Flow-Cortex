package com.flowcortex.workflow.domain;

import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link WorkflowVersionPin}: one pin per member per workflow. */
public record WorkflowVersionPinId(UUID userId, UUID workflowId) implements Serializable {
}
