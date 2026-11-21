package com.flowcortex.workflow.api;

import java.util.UUID;

/**
 * Version history of a workflow and the per-member pin. Writes are owner-only (403 when the caller can see the
 * workflow without owning it, 404 when it is unknown, deleted or hidden); reads serve the owner every version in
 * full and any other member who can see the workflow only released versions, as skeletons.
 */
public interface WorkflowVersionService {

    /** Newest first; {@code beforeVersion} null starts at the newest, {@code limit} is 1 to 100. */
    WorkflowVersionPageView list(UUID callerId, UUID workflowId, Integer beforeVersion, int limit);

    WorkflowVersionDetailView get(UUID callerId, UUID workflowId, int version);

    /** Saves the live definition as a {@code manual} version, or answers the newest when it is unchanged. */
    VersionSavedView save(UUID callerId, UUID workflowId, String changelog);

    /** Only {@code manual} and {@code hotfix} versions accept a name; an empty {@code changelog} clears it. */
    WorkflowVersionView rename(UUID callerId, UUID workflowId, int version, String changelog);

    /** Replaces the live name and steps with a version's and records a {@code rollback} version. */
    VersionSavedView rollback(UUID callerId, UUID workflowId, int version, String changelog);

    /** Records a {@code hotfix} version without moving the published pointer. */
    VersionSavedView hotfix(UUID callerId, UUID workflowId, String note);

    /** {@code source} and {@code target} are {@code draft}, {@code published} or a version number; null = default. */
    VersionDiffView diff(UUID callerId, UUID workflowId, String source, String target);

    /** Moves the pointer members other than the owner are served; {@code versionId} may be null (refused). */
    WorkflowView setPublishedVersion(UUID callerId, UUID workflowId, UUID versionId);

    VersionPinView getPin(UUID callerId, UUID workflowId);

    /** Pins the caller's own runs of someone else's workflow to a released version; replaces an earlier pin. */
    VersionPinView pin(UUID callerId, UUID workflowId, int version, String reason);

    /** Clears the caller's pin; succeeds when there is none. */
    void unpin(UUID callerId, UUID workflowId);
}
