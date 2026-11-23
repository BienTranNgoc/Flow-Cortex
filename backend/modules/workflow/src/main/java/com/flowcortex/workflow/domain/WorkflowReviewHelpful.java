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

/** A member's helpful mark on a review; at most one per member and review. Rows are written by a native insert. */
@Entity
@Table(name = "workflow_review_helpful")
@IdClass(WorkflowReviewHelpful.Key.class)
public class WorkflowReviewHelpful {

    @Id
    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowReviewHelpful() {
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite id of {@link WorkflowReviewHelpful}. */
    public static class Key implements Serializable {

        private UUID reviewId;
        private UUID userId;

        public Key() {
        }

        public Key(UUID reviewId, UUID userId) {
            this.reviewId = reviewId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(reviewId, key.reviewId)
                    && Objects.equals(userId, key.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reviewId, userId);
        }
    }
}
