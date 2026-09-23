package com.flowcortex.workflow.api;

import java.util.UUID;

/**
 * Public API backing {@code /api/v1/workflows/{id}/runs} (start) and
 * {@code /api/v1/runs/{id}} (read).
 */
public interface WorkflowRunService {

    /**
     * Starts a run: a short transaction creates the {@code WorkflowRun} ({@code running}) plus its {@code pending}
     * {@code RunStep}s, commits, and returns immediately — execution happens asynchronously after that commit, so
     * the returned {@link RunView} reflects the just-started state ({@code pending} steps), not the eventual outcome.
     * @throws ResponseStatusException 503 if {@code flowcortex.workflow.enabled} is off
     * @throws NotFoundException if {@code workflowId} is not visible to {@code currentUserId}
     */
    RunView startRun(UUID currentUserId, UUID workflowId, String input);

    /** @throws NotFoundException if {@code runId} doesn't exist or isn't owned by {@code currentUserId} (always owner-scoped) */
    RunView getRun(UUID currentUserId, UUID runId);
}
