package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@code workflow_run} row returned by {@link
 * WorkflowRunService}. {@code status} is the exact lowercase wire value
 * ({@code running}|{@code completed}|{@code error}).
 */
public record RunView(
        UUID id, UUID workflowId, UUID ownerId, String status, String input, String errorMessage,
        Instant startedAt, Instant finishedAt, List<RunStepView> steps) {
}
