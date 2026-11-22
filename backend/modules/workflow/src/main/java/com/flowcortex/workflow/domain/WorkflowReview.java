package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A member's review of a workflow; at most one per member and workflow. The helpful count is
 * changed only by additive updates in the database, so an entity save never overwrites it.
 */
@Entity
@Table(name = "workflow_review")
public class WorkflowReview {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "body", length = 5000)
    private String body;

    @Column(name = "helpful_count", nullable = false, updatable = false)
    private int helpfulCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected WorkflowReview() {
    }

    /** A new review with no helpful marks; {@code body} is {@code null} when the member wrote none. */
    public static WorkflowReview create(UUID id, UUID workflowId, UUID userId, int rating, String body, Instant now) {
        WorkflowReview review = new WorkflowReview();
        review.id = id;
        review.workflowId = workflowId;
        review.userId = userId;
        review.rating = rating;
        review.body = body;
        review.createdAt = now;
        review.updatedAt = now;
        return review;
    }

    /** Replaces the rating and body; the creation time and the helpful count stay. */
    public void replace(int rating, String body, Instant now) {
        this.rating = rating;
        this.body = body;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getRating() {
        return rating;
    }

    public String getBody() {
        return body;
    }

    public int getHelpfulCount() {
        return helpfulCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
