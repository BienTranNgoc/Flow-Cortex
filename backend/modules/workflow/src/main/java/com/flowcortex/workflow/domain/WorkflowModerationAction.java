package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One moderation action on a workflow, kept for good. {@code source} is {@code report} (taken from
 * a report; {@code reportId} is cleared if that report is later deleted) or {@code direct}
 * (no report). {@code action} holds the lowercase code the table constraint allows.
 */
@Entity
@Table(name = "workflow_moderation_action")
public class WorkflowModerationAction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "report_id", updatable = false)
    private UUID reportId;

    @Column(name = "source", length = 8, nullable = false, updatable = false)
    private String source;

    @Column(name = "action", length = 16, nullable = false, updatable = false)
    private String action;

    @Column(name = "moderator_id", nullable = false, updatable = false)
    private UUID moderatorId;

    @Column(name = "reason", length = 1000, updatable = false)
    private String reason;

    @Column(name = "internal_note", length = 1000, updatable = false)
    private String internalNote;

    @Column(name = "moderation_status", nullable = false, updatable = false)
    private WorkflowModerationStatus moderationStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowModerationAction() {
    }

    public WorkflowModerationAction(UUID id, UUID workflowId, UUID reportId, String source, String action,
            UUID moderatorId, String reason, String internalNote, WorkflowModerationStatus moderationStatus,
            Instant createdAt) {
        this.id = id;
        this.workflowId = workflowId;
        this.reportId = reportId;
        this.source = source;
        this.action = action;
        this.moderatorId = moderatorId;
        this.reason = reason;
        this.internalNote = internalNote;
        this.moderationStatus = moderationStatus;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getReportId() {
        return reportId;
    }

    public String getSource() {
        return source;
    }

    public String getAction() {
        return action;
    }

    public UUID getModeratorId() {
        return moderatorId;
    }

    public String getReason() {
        return reason;
    }

    public String getInternalNote() {
        return internalNote;
    }

    /** The workflow's moderation status after this action. */
    public WorkflowModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
