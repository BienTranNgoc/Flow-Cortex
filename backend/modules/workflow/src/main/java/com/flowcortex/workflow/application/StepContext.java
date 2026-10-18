package com.flowcortex.workflow.application;

import java.util.UUID;

/**
 * What an executor gets for one step: its identity, the fencing {@code attempt}, the resolved
 * input, the step definition's {@code config} JSON ({@code null} when unset) and the input the
 * run step stored when its run started ({@code null} when none).
 */
record StepContext(UUID runId, UUID stepId, int stepOrder, String type, short attempt, String input, String config,
        String storedInput) {
}
