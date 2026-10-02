package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@code run_step} row. {@code status} is the exact
 * lowercase wire value ({@code pending}|{@code running}|{@code completed}|
 * {@code error}) the acceptance oracles assert on directly. A {@code running}
 * step with no {@code pickedUpAt} is still waiting for an executor; {@code
 * outputs} is empty unless the step produced objects. {@code mode} is {@code inline} or {@code
 * remote}, read from the step's own type, so it holds even after the workflow is edited.
 */
public record RunStepView(
        UUID id, int stepOrder, String type, String status, String input, String output, String errorMessage,
        Instant startedAt, Instant finishedAt, Integer progressPercent, int attempt, String errorCode,
        int deliveryCount, Instant pickedUpAt, List<RunStepOutputView> outputs, String mode) {
}
