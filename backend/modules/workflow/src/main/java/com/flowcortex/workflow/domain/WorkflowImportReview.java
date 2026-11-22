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
 * The ledger row of one import review token. While open it holds the parsed artifact and the
 * review answered to the admin; once imported it keeps only who, when and which workflow, so a
 * retry with the same token can be answered without keeping the file or its destinations.
 */
@Entity
@Table(name = "workflow_import_review")
public class WorkflowImportReview {

    @Id
    @Column(name = "token", nullable = false, updatable = false)
    private UUID token;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "artifact")
    private String artifact;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review")
    private String review;

    @Column(name = "outbound", nullable = false, updatable = false)
    private boolean outbound;

    @Column(name = "artifact_bytes", nullable = false, updatable = false)
    private int artifactBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "imported_at")
    private Instant importedAt;

    /** Required by JPA. */
    protected WorkflowImportReview() {
    }

    public WorkflowImportReview(UUID token, UUID userId, String artifact, String review, boolean outbound,
            int artifactBytes, Instant createdAt, Instant expiresAt) {
        this.token = token;
        this.userId = userId;
        this.artifact = artifact;
        this.review = review;
        this.outbound = outbound;
        this.artifactBytes = artifactBytes;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Records the import and drops the file and the destination values the review held. */
    public void markImported(UUID workflowId, Instant at) {
        this.workflowId = workflowId;
        this.importedAt = at;
        this.artifact = null;
        this.review = null;
    }

    public UUID getToken() {
        return token;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getArtifact() {
        return artifact;
    }

    public String getReview() {
        return review;
    }

    public boolean isOutbound() {
        return outbound;
    }

    public int getArtifactBytes() {
        return artifactBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
