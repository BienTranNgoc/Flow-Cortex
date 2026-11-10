package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A stored image of a review, one to five per review in display order. */
@Entity
@Table(name = "workflow_review_image")
public class WorkflowReviewImage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "bucket", length = 63, nullable = false, updatable = false)
    private String bucket;

    @Column(name = "object_key", length = 512, nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "content_type", length = 100, nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected WorkflowReviewImage() {
    }

    public WorkflowReviewImage(UUID id, UUID reviewId, int position, String bucket, String objectKey,
            String contentType, long sizeBytes, Instant createdAt) {
        this.id = id;
        this.reviewId = reviewId;
        this.position = position;
        this.bucket = bucket;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
    }

    /** Moves the image to another display position of its review. */
    public void moveTo(int position) {
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public int getPosition() {
        return position;
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
