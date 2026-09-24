package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_task")
public class ReviewTask {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "run_step_id", nullable = false)
    private UUID runStepId;

    @Column(name = "review_type", length = 50, nullable = false)
    private String reviewType;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "original_payload", nullable = false, columnDefinition = "text")
    private String originalPayload;

    @Column(name = "reviewed_payload", columnDefinition = "text")
    private String reviewedPayload;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ReviewTask() {
    }

    public ReviewTask(UUID id, UUID runId, UUID runStepId, String reviewType, String status, String originalPayload, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.runStepId = runStepId;
        this.reviewType = reviewType;
        this.status = status;
        this.originalPayload = originalPayload;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public UUID getRunStepId() {
        return runStepId;
    }

    public void setRunStepId(UUID runStepId) {
        this.runStepId = runStepId;
    }

    public String getReviewType() {
        return reviewType;
    }

    public void setReviewType(String reviewType) {
        this.reviewType = reviewType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOriginalPayload() {
        return originalPayload;
    }

    public void setOriginalPayload(String originalPayload) {
        this.originalPayload = originalPayload;
    }

    public String getReviewedPayload() {
        return reviewedPayload;
    }

    public void setReviewedPayload(String reviewedPayload) {
        this.reviewedPayload = reviewedPayload;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(UUID reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void approve(UUID reviewerId, String updatedPayload) {
        this.status = "approved";
        this.reviewedBy = reviewerId;
        this.reviewedPayload = updatedPayload;
        this.reviewedAt = Instant.now();
    }

    public void reject(UUID reviewerId) {
        this.status = "rejected";
        this.reviewedBy = reviewerId;
        this.reviewedAt = Instant.now();
    }
}
