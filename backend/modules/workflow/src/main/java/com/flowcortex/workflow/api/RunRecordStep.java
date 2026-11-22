package com.flowcortex.workflow.api;

import java.util.UUID;

/** A run's step: its id, position, type and current attempt. */
public record RunRecordStep(UUID runStepId, int stepOrder, String type, short attempt) {
}
