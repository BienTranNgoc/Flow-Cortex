package com.flowcortex.workflow.domain;

import com.flowcortex.shared.web.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public ReviewTask() {
    }

    public ReviewTask(
        UUID id,
        UUID runId,
        UUID runStepId,
        String reviewType,
        String status,
        String originalPayload,
        Instant createdAt
    ) {
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

    public UUID getRunId() {
        return runId;
    }

    public UUID getRunStepId() {
        return runStepId;
    }

    public String getReviewType() {
        return reviewType;
    }

    public String getStatus() {
        return status;
    }

    public String getOriginalPayload() {
        return originalPayload;
    }

    public String getReviewedPayload() {
        return reviewedPayload;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    public void approve(UUID reviewerId, String updatedPayload) {
        if (!"pending".equals(this.status)) {
            throw new ConflictException("ReviewTask " + this.id + " is already " + this.status);
        }
        this.status = "approved";
        this.reviewedBy = reviewerId;
        this.reviewedPayload = updatedPayload;
        this.reviewedAt = Instant.now();
    }

    public void reject(UUID reviewerId) {
        if (!"pending".equals(this.status)) {
            throw new ConflictException("ReviewTask " + this.id + " is already " + this.status);
        }
        this.status = "rejected";
        this.reviewedBy = reviewerId;
        this.reviewedAt = Instant.now();
    }
}
