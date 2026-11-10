package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A workflow a member saved; at most one per member and workflow. Rows are written by a native insert. */
@Entity
@Table(name = "workflow_bookmark")
@IdClass(WorkflowBookmarkId.class)
public class WorkflowBookmark {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowBookmark() {
    }

    public WorkflowBookmark(UUID userId, UUID workflowId, Instant createdAt) {
        this.userId = userId;
        this.workflowId = workflowId;
        this.createdAt = createdAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
