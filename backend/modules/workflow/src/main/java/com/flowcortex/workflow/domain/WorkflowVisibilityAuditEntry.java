package com.flowcortex.workflow.domain;

import com.flowcortex.workflow.api.WorkflowVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One change of a workflow's visibility tier, or one rotation of its key. */
@Entity
@Table(name = "workflow_visibility_audit")
public class WorkflowVisibilityAuditEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;

    @Column(name = "from_visibility", length = 20, nullable = false, updatable = false)
    private WorkflowVisibility fromVisibility;

    @Column(name = "to_visibility", length = 20, nullable = false, updatable = false)
    private WorkflowVisibility toVisibility;

    @Column(name = "triggered_by", length = 20, nullable = false, updatable = false)
    private VisibilityChangeTrigger triggeredBy;

    @Column(name = "network", length = 64, updatable = false)
    private String network;

    @Column(name = "user_agent", length = 512, updatable = false)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowVisibilityAuditEntry() {
    }

    public WorkflowVisibilityAuditEntry(UUID id, UUID workflowId, UUID changedBy, WorkflowVisibility fromVisibility,
            WorkflowVisibility toVisibility, VisibilityChangeTrigger triggeredBy, String network, String userAgent,
            Instant createdAt) {
        this.id = id;
        this.workflowId = workflowId;
        this.changedBy = changedBy;
        this.fromVisibility = fromVisibility;
        this.toVisibility = toVisibility;
        this.triggeredBy = triggeredBy;
        this.network = network;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public WorkflowVisibility getFromVisibility() {
        return fromVisibility;
    }

    public WorkflowVisibility getToVisibility() {
        return toVisibility;
    }

    public VisibilityChangeTrigger getTriggeredBy() {
        return triggeredBy;
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
