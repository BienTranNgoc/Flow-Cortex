package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A workflow definition. {@code isShared} is the ONLY sharing dimension: a
 * shared definition is listed to every authenticated user, but that never
 * implies shared RUNS — {@link WorkflowRun#ownerId} always scopes reads to
 * the user who started it.
 */
@Entity
@Table(name = "workflow")
public class Workflow {

    public static final UUID SYSTEM_OWNER_ID = new UUID(0, 0);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "is_shared", nullable = false)
    private boolean shared;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "status", nullable = false)
    private WorkflowStatus status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "published_version_id")
    private UUID publishedVersionId;

    /** Required by JPA. */
    protected Workflow() {
    }

    /** Creates a published workflow. */
    public Workflow(UUID id, UUID ownerId, String name, boolean shared) {
        this(id, ownerId, name, shared, WorkflowStatus.PUBLISHED);
    }

    public static Workflow draft(UUID id, UUID ownerId, String name, boolean shared) {
        return new Workflow(id, ownerId, name, shared, WorkflowStatus.DRAFT);
    }

    private Workflow(UUID id, UUID ownerId, String name, boolean shared, WorkflowStatus status) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.shared = shared;
        this.status = status;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String name) {
        this.name = name;
        touch();
    }

    public void setShared(boolean shared) {
        this.shared = shared;
        touch();
    }

    public void changeStatus(WorkflowStatus status) {
        this.status = status;
        touch();
    }

    /** Moves the version non-owners are served; the caller guarantees it belongs to this workflow. */
    public void pointTo(UUID versionId) {
        this.publishedVersionId = versionId;
        touch();
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public boolean isShared() {
        return shared;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getPublishedVersionId() {
        return publishedVersionId;
    }

    /** True for the migration-maintained seed workflows, which are served live and never versioned. */
    public boolean isSystemOwned() {
        return SYSTEM_OWNER_ID.equals(ownerId);
    }
}
