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

/** A member's report of a review; at most one per member and review. Rows are written by a native insert. */
@Entity
@Table(name = "workflow_review_report")
@IdClass(WorkflowReviewReport.Key.class)
public class WorkflowReviewReport {

    @Id
    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Id
    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    @Column(name = "reason", length = 20, nullable = false, updatable = false)
    private ReviewReportReason reason;

    @Column(name = "details", length = 1000, updatable = false)
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowReviewReport() {
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public ReviewReportReason getReason() {
        return reason;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite id of {@link WorkflowReviewReport}. */
    public static class Key implements Serializable {

        private UUID reviewId;
        private UUID reporterId;

        public Key() {
        }

        public Key(UUID reviewId, UUID reporterId) {
            this.reviewId = reviewId;
            this.reporterId = reporterId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(reviewId, key.reviewId)
                    && Objects.equals(reporterId, key.reporterId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reviewId, reporterId);
        }
    }
}
