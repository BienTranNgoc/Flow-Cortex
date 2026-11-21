package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One read of a workflow through its share key. The accessor key is only the dedupe key (user id
 * or a keyed hash of the client) and is never exposed; {@code network} is the masked address.
 * Rows are written by a native insert, so the entity is read-only.
 */
@Entity
@Table(name = "workflow_access_log")
public class WorkflowAccessLogEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "accessed_by", updatable = false)
    private UUID accessedBy;

    @Column(name = "network", length = 64, updatable = false)
    private String network;

    @Column(name = "user_agent", length = 512, updatable = false)
    private String userAgent;

    @Column(name = "accessor_key", length = 80, nullable = false, updatable = false)
    private String accessorKey;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowAccessLogEntry() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getAccessedBy() {
        return accessedBy;
    }

    public String getNetwork() {
        return network;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
