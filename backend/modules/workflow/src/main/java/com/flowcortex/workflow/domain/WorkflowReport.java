package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A member's report of a workflow; at most one per member and workflow. The row is inserted by
 * {@code WorkflowReportRepository#insertIfAbsent} and changed only by a reviewer holding the row
 * lock ({@code findByIdForUpdate}). {@code reason}, {@code status} and {@code resolution} hold the
 * lowercase codes the table constraints allow.
 */
@Entity
@Table(name = "workflow_report")
public class WorkflowReport {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    @Column(name = "reason", length = 20, nullable = false, updatable = false)
    private String reason;

    @Column(name = "details", length = 1000, updatable = false)
    private String details;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "resolution", length = 16)
    private String resolution;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "internal_note", length = 1000)
    private String internalNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowReport() {
    }

    /**
     * Records a reviewer's decision. {@code internalNote} replaces the stored note only when one
     * was sent, so an action without a note keeps the earlier one.
     */
    public void review(String status, String resolution, UUID reviewerId, Instant reviewedAt, String internalNote) {
        this.status = status;
        this.resolution = resolution;
        this.reviewerId = reviewerId;
        this.reviewedAt = reviewedAt;
        if (internalNote != null) {
            this.internalNote = internalNote;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public String getReason() {
        return reason;
    }

    public String getDetails() {
        return details;
    }

    public String getStatus() {
        return status;
    }

    public String getResolution() {
        return resolution;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getInternalNote() {
        return internalNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
