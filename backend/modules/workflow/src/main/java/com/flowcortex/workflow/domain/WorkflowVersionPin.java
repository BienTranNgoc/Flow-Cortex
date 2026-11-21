package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A member's choice of the version of someone else's workflow their own runs use. Read-only:
 * writes go through the repository upsert so two concurrent first pins cannot collide.
 */
@Entity
@Table(name = "workflow_version_pin")
@IdClass(WorkflowVersionPinId.class)
public class WorkflowVersionPin {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "pinned_version", nullable = false, insertable = false, updatable = false)
    private int pinnedVersion;

    @Column(name = "reason", insertable = false, updatable = false)
    private String reason;

    @Column(name = "pinned_at", nullable = false, insertable = false, updatable = false)
    private Instant pinnedAt;

    /** Required by JPA. */
    protected WorkflowVersionPin() {
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getPinnedVersion() {
        return pinnedVersion;
    }

    public String getReason() {
        return reason;
    }

    public Instant getPinnedAt() {
        return pinnedAt;
    }
}
