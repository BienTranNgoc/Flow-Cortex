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
 * One immutable snapshot of a workflow definition. Only the changelog may change, and
 * {@code releasedAt} is set once, the first time the workflow's pointer lands on this version.
 */
@Entity
@Table(name = "workflow_version")
public class WorkflowVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "changelog")
    private String changelog;

    @Column(name = "source", nullable = false, updatable = false)
    private WorkflowVersionSource source;

    @Column(name = "restored_from_version", updatable = false)
    private Integer restoredFromVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, updatable = false)
    private String snapshot;

    @Column(name = "published_by", nullable = false, updatable = false)
    private UUID publishedBy;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    /** Required by JPA. */
    protected WorkflowVersion() {
    }

    public WorkflowVersion(UUID id, UUID workflowId, int version, String changelog, WorkflowVersionSource source,
            Integer restoredFromVersion, String snapshot, UUID publishedBy, Instant publishedAt) {
        this.id = id;
        this.workflowId = workflowId;
        this.version = version;
        this.changelog = changelog;
        this.source = source;
        this.restoredFromVersion = restoredFromVersion;
        this.snapshot = snapshot;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
    }

    public void rename(String changelog) {
        this.changelog = changelog;
    }

    /** Marks the version as served to non-owners; a version already released keeps its first instant. */
    public void release(Instant at) {
        if (releasedAt == null) {
            releasedAt = at;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public int getVersion() {
        return version;
    }

    public String getChangelog() {
        return changelog;
    }

    public WorkflowVersionSource getSource() {
        return source;
    }

    public Integer getRestoredFromVersion() {
        return restoredFromVersion;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public boolean isReleased() {
        return releasedAt != null;
    }
}
