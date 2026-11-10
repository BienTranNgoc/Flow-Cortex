package com.flowcortex.workflow.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite id of {@link WorkflowBookmark}. */
public class WorkflowBookmarkId implements Serializable {

    private UUID userId;
    private UUID workflowId;

    public WorkflowBookmarkId() {
    }

    public WorkflowBookmarkId(UUID userId, UUID workflowId) {
        this.userId = userId;
        this.workflowId = workflowId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorkflowBookmarkId id
                && Objects.equals(userId, id.userId)
                && Objects.equals(workflowId, id.workflowId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, workflowId);
    }
}
