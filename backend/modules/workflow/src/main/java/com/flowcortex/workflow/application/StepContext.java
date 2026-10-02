package com.flowcortex.workflow.application;

import java.util.UUID;

/**
 * What an executor gets for one step: its identity, the fencing {@code attempt}, the resolved
 * input and the step definition's {@code config} JSON ({@code null} when unset).
 */
record StepContext(UUID runId, UUID stepId, int stepOrder, String type, short attempt, String input, String config) {
}
