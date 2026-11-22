package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A member admitted to an unlisted workflow by presenting its key; at most one per workflow and user. */
@Entity
@Table(name = "workflow_link_grant")
@IdClass(WorkflowLinkGrant.Key.class)
public class WorkflowLinkGrant {

    @Id
    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowLinkGrant() {
    }

    public WorkflowLinkGrant(UUID workflowId, UUID userId, Instant createdAt) {
        this.workflowId = workflowId;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite id of {@link WorkflowLinkGrant}. */
    public static class Key implements Serializable {

        private UUID workflowId;
        private UUID userId;

        public Key() {
        }

        public Key(UUID workflowId, UUID userId) {
            this.workflowId = workflowId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(workflowId, key.workflowId)
                    && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workflowId, userId);
        }
    }
}
