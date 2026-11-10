package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@code workflow} row returned by {@link WorkflowService}.
 * {@code steps} is always populated in definition order. {@code available} is false while a step
 * type it uses is switched off; starting a run of it is then refused. {@code status} is {@code draft} or
 * {@code published}. {@code publishedVersionId} is the version the pointer names; {@code servedVersion} is the
 * number of the version the caller is served in place of the live draft, null when {@code steps} are the live ones.
 * {@code allowFork} is the owner's switch, {@code forkCount} how many times it was forked and {@code forkedFromId}
 * the workflow it was forked from, null when it is not a fork.
 */
public record WorkflowView(
        UUID id, UUID ownerId, String name, boolean shared, Instant createdAt, Instant updatedAt,
        List<WorkflowStepView> steps, boolean available, String status, UUID publishedVersionId,
        Integer servedVersion, boolean allowFork, int forkCount, UUID forkedFromId) {

    public WorkflowView(UUID id, UUID ownerId, String name, boolean shared, Instant createdAt, Instant updatedAt,
            List<WorkflowStepView> steps, boolean available, String status, UUID publishedVersionId,
            Integer servedVersion) {
        this(id, ownerId, name, shared, createdAt, updatedAt, steps, available, status, publishedVersionId,
                servedVersion, false, 0, null);
    }
}
