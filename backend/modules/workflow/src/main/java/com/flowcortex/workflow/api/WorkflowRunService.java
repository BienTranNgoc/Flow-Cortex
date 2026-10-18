package com.flowcortex.workflow.api;

import com.flowcortex.shared.web.PageView;
import java.util.UUID;
import java.util.List;

/**
 * Public API backing {@code /api/v1/workflows/{id}/runs} (start), {@code /api/v1/runs/{id}}
 * (read) and the admin lists under {@code /api/v1/admin}.
 */
public interface WorkflowRunService {

    /**
     * Starts a run: a short transaction creates the {@code WorkflowRun} ({@code running}) plus its {@code pending}
     * {@code RunStep}s, commits, and returns immediately — execution happens asynchronously after that commit, so
     * the returned {@link RunView} reflects the just-started state ({@code pending} steps), not the eventual outcome.
     * <p>When the first step processes media, the run takes the caller's upload {@code uploadId} as that step's
     * input, and keeps {@code originalName} (cleaned) as its display input; {@code input} is not used. Otherwise
     * {@code input} is the run's input and the upload is not consulted.
     * @throws ResponseStatusException 503 if {@code flowcortex.workflow.enabled} is off, or if the workflow has a
     *         remote (media) step while the media engine is disabled; nothing is created either way
     * @throws com.flowcortex.media.api.UploadRejectedException if a media first step has no upload, or the upload
     *         is missing, not the caller's, or not acceptable; nothing is created
     * @throws NotFoundException if {@code workflowId} is not visible to {@code currentUserId}
     */
    RunView startRun(UUID currentUserId, UUID workflowId, String input, UUID uploadId, String originalName);

    /** Starts a run with ordered image uploads while keeping the single-upload API compatible. */
    RunView startRun(UUID currentUserId, UUID workflowId, String input, UUID uploadId, List<UUID> uploadIds,
            String originalName);

    /**
     * {@link #startRun(UUID, UUID, String, UUID, List, String)} with {@code acknowledgeCost}: the member has
     * confirmed the run's cost, which a {@link RunStartGuard} may require.
     */
    RunView startRun(UUID currentUserId, UUID workflowId, String input, UUID uploadId, List<UUID> uploadIds,
            String originalName, boolean acknowledgeCost);

    /** {@link #startRun(UUID, UUID, String, UUID, String)} without an upload: a workflow whose first step takes text. */
    RunView startRun(UUID currentUserId, UUID workflowId, String input);

    /** @throws NotFoundException if {@code runId} doesn't exist or isn't owned by {@code currentUserId} (always owner-scoped) */
    RunView getRun(UUID currentUserId, UUID runId);

    /**
     * A download URL for output {@code name} of step {@code stepOrder}, always owner-scoped.
     * @throws NotFoundException if the run is not {@code currentUserId}'s, or the step or the output does not exist
     */
    RunOutputUrlView getOutputUrl(UUID currentUserId, UUID runId, int stepOrder, String name);

    /**
     * Every user's runs, newest first, for the admin console — not owner-scoped, so callers must
     * be ADMIN-only. {@code status} is a lowercase run status, or {@code null} for all.
     */
    PageView<AdminRunView> listAllRuns(int page, int size, String status);

    /**
     * Every remote step across all runs, newest first, for the admin console — not owner-scoped.
     * {@code status} is a lowercase step status, or {@code null} for all.
     */
    PageView<MediaJobView> listRemoteSteps(int page, int size, String status);
}
