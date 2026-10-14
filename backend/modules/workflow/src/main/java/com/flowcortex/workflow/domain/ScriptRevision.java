package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An immutable version of the script presented at a Product Video approval checkpoint. */
@Entity
@Table(name = "script_revision")
public class ScriptRevision {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "review_task_id", nullable = false, updatable = false)
    private UUID reviewTaskId;

    @Column(name = "revision_number", nullable = false, updatable = false)
    private int revisionNumber;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ScriptRevision() {
    }

    public ScriptRevision(UUID id, UUID runId, UUID reviewTaskId, int revisionNumber,
            String content, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.reviewTaskId = reviewTaskId;
        this.revisionNumber = revisionNumber;
        this.content = content;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getReviewTaskId() {
        return reviewTaskId;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public String getContent() {
        return content;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
