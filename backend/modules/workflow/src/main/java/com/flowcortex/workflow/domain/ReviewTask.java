package com.flowcortex.workflow.domain;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
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

    /** The refusal every losing or late decision on a task answers with. */
    public static final LocalizedMessage NOT_WAITING_FOR_REVIEW = new LocalizedMessage("reviewTask.notWaitingForReview");

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "run_step_id", nullable = false)
    private UUID runStepId;

    @Column(name = "review_type", length = 50, nullable = false)
    private String reviewType;

    @Column(name = "status", length = 20, nullable = false)
    private ReviewTaskStatus status;

    /** The previous step's output, which may legitimately be null (a run started without input). */
    @Column(name = "original_payload", columnDefinition = "text")
    private String originalPayload;

    @Column(name = "reviewed_payload", columnDefinition = "text")
    private String reviewedPayload;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "current_revision_id")
    private UUID currentRevisionId;

    @Column(name = "approved_revision_id")
    private UUID approvedRevisionId;

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
        String originalPayload,
        Instant createdAt
    ) {
        this.id = id;
        this.runId = runId;
        this.runStepId = runStepId;
        this.reviewType = reviewType;
        this.status = ReviewTaskStatus.PENDING;
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

    public ReviewTaskStatus getStatus() {
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

    public UUID getCurrentRevisionId() {
        return currentRevisionId;
    }

    public UUID getApprovedRevisionId() {
        return approvedRevisionId;
    }

    public void attachRevision(UUID revisionId, long expectedVersion) {
        requirePending();
        requireVersion(expectedVersion);
        this.currentRevisionId = revisionId;
    }

    public void approveRevision(UUID reviewerId, UUID revisionId, long expectedVersion, Instant now) {
        requirePending();
        requireVersion(expectedVersion);
        if (currentRevisionId == null || !currentRevisionId.equals(revisionId)) {
            throw new ConflictException(new LocalizedMessage("reviewTask.notCurrentRevision"));
        }
        this.status = ReviewTaskStatus.APPROVED;
        this.reviewedBy = reviewerId;
        this.approvedRevisionId = revisionId;
        this.reviewedAt = now;
    }

    public void approve(UUID reviewerId, String updatedPayload, Instant now) {
        requirePending();
        this.status = ReviewTaskStatus.APPROVED;
        this.reviewedBy = reviewerId;
        this.reviewedPayload = updatedPayload;
        this.reviewedAt = now;
    }

    public void reject(UUID reviewerId, Instant now) {
        requirePending();
        this.status = ReviewTaskStatus.REJECTED;
        this.reviewedBy = reviewerId;
        this.reviewedAt = now;
    }

    private void requirePending() {
        if (this.status != ReviewTaskStatus.PENDING) {
            throw new ConflictException(NOT_WAITING_FOR_REVIEW);
        }
    }

    private void requireVersion(long expectedVersion) {
        long actual = version == null ? 0L : version;
        if (actual != expectedVersion) {
            throw new ConflictException(new LocalizedMessage("reviewTask.versionChanged", id, expectedVersion, actual));
        }
    }
}
