package com.flowcortex.workflow.domain;

import com.flowcortex.workflow.api.WorkflowVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;

/**
 * A workflow definition. {@link WorkflowVisibility} is the sharing dimension: a public definition
 * is listed to every authenticated user and an unlisted one is readable through its key, but
 * neither implies shared RUNS — {@link WorkflowRun#ownerId} always scopes reads to the user who
 * started it. {@code is_shared} mirrors {@code visibility == PUBLIC} so a rollback keeps working.
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

    @Column(name = "visibility", nullable = false)
    private WorkflowVisibility visibility;

    @Column(name = "unlisted_key", length = 32)
    private String unlistedKey;

    @Column(name = "unlisted_key_generated_at")
    private Instant unlistedKeyGeneratedAt;

    @Column(name = "unlisted_key_expires_at")
    private Instant unlistedKeyExpiresAt;

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

    @Column(name = "moderation_status", nullable = false)
    private WorkflowModerationStatus moderationStatus;

    @Column(name = "allow_fork", nullable = false)
    private boolean allowFork;

    /** Written only by the atomic increment in the repository, never through the entity. */
    @Column(name = "fork_count", nullable = false, insertable = false, updatable = false)
    @ColumnDefault("0")
    private int forkCount;

    @Column(name = "forked_from_id", updatable = false)
    private UUID forkedFromId;

    /** Required by JPA. */
    protected Workflow() {
    }

    /** Creates a published workflow. */
    public Workflow(UUID id, UUID ownerId, String name, boolean shared) {
        this(id, ownerId, name, shared, WorkflowStatus.PUBLISHED);
    }

    /** Creates a private draft. */
    public static Workflow draft(UUID id, UUID ownerId, String name) {
        return new Workflow(id, ownerId, name, false, WorkflowStatus.DRAFT);
    }

    /** An unshared draft copy of {@code sourceId}; forking is off until its owner turns it on. */
    public static Workflow forkOf(UUID id, UUID ownerId, String name, UUID sourceId) {
        Workflow fork = new Workflow(id, ownerId, name, false, WorkflowStatus.DRAFT);
        fork.forkedFromId = sourceId;
        return fork;
    }

    private Workflow(UUID id, UUID ownerId, String name, boolean shared, WorkflowStatus status) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.shared = shared;
        this.visibility = shared ? WorkflowVisibility.PUBLIC : WorkflowVisibility.PRIVATE;
        this.status = status;
        this.moderationStatus = WorkflowModerationStatus.VISIBLE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void rename(String name) {
        this.name = name;
        touch();
    }

    /** Makes the workflow unlisted with a fresh key; a prior key is replaced. */
    public void makeUnlisted(String key, Instant generatedAt, Instant expiresAt) {
        this.visibility = WorkflowVisibility.UNLISTED;
        this.unlistedKey = key;
        this.unlistedKeyGeneratedAt = generatedAt;
        this.unlistedKeyExpiresAt = expiresAt;
        syncSharedAndTouch();
    }

    /** Keeps the current key of an unlisted workflow and sets its expiry. */
    public void keepKey(Instant expiresAt) {
        this.unlistedKeyExpiresAt = expiresAt;
        touch();
    }

    /** Moves to {@code PRIVATE} or {@code PUBLIC}, which hold no key. */
    public void makeListedOrPrivate(WorkflowVisibility visibility) {
        if (visibility == WorkflowVisibility.UNLISTED) {
            throw new IllegalArgumentException("unlisted needs a key; use makeUnlisted");
        }
        this.visibility = visibility;
        this.unlistedKey = null;
        this.unlistedKeyGeneratedAt = null;
        this.unlistedKeyExpiresAt = null;
        syncSharedAndTouch();
    }

    /** Replaces the key of an unlisted workflow; the expiry stays. */
    public void rotateKey(String key, Instant at) {
        this.unlistedKey = key;
        this.unlistedKeyGeneratedAt = at;
        syncSharedAndTouch();
    }

    private void syncSharedAndTouch() {
        this.shared = visibility == WorkflowVisibility.PUBLIC;
        touch();
    }

    public void setAllowFork(boolean allowFork) {
        this.allowFork = allowFork;
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

    /**
     * Sets the staff moderation state. The only writer of the field, and it leaves the owner's
     * visibility alone so a restore returns the workflow to exactly what its owner chose.
     */
    public void moderate(WorkflowModerationStatus moderationStatus) {
        this.moderationStatus = moderationStatus;
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

    public WorkflowVisibility getVisibility() {
        return visibility;
    }

    public String getUnlistedKey() {
        return unlistedKey;
    }

    public Instant getUnlistedKeyGeneratedAt() {
        return unlistedKeyGeneratedAt;
    }

    public Instant getUnlistedKeyExpiresAt() {
        return unlistedKeyExpiresAt;
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

    public WorkflowModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public boolean isAllowFork() {
        return allowFork;
    }

    public int getForkCount() {
        return forkCount;
    }

    public UUID getForkedFromId() {
        return forkedFromId;
    }

    /** True for the migration-maintained seed workflows, which are served live and never versioned. */
    public boolean isSystemOwned() {
        return SYSTEM_OWNER_ID.equals(ownerId);
    }
}
