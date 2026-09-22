package com.flowcortex.workflow.api;

import java.util.UUID;

/**
 * Public API backing {@code /api/v1/workflows/{id}/runs} (start) and
 * {@code /api/v1/runs/{id}} (read) — design.md D5/D3.
 */
public interface WorkflowRunService {

    /**
     * Starts a run: a short transaction creates the {@code WorkflowRun}
     * ({@code running}) plus its {@code pending} {@code RunStep}s, commits,
     * and returns immediately — execution happens AFTER that commit,
     * asynchronously (design.md D5/R-050). The returned {@link RunView}
     * therefore reflects the run in its just-started state (all steps
     * {@code pending}), not its eventual outcome.
     *
     * @throws org.springframework.web.server.ResponseStatusException 503 if
     *         {@code flowcortex.workflow.enabled} is off (R-046) — no run is
     *         created
     * @throws com.flowcortex.shared.web.NotFoundException if {@code
     *         workflowId} is not visible to {@code currentUserId} (R-049)
     */
    RunView startRun(UUID currentUserId, UUID workflowId, String input);

    /**
     * @throws com.flowcortex.shared.web.NotFoundException if {@code runId}
     *         does not exist, or exists but is not owned by {@code
     *         currentUserId} (R-047 — runs are ALWAYS owner-scoped, even for
     *         a run of a shared workflow)
     */
    RunView getRun(UUID currentUserId, UUID runId);
}
