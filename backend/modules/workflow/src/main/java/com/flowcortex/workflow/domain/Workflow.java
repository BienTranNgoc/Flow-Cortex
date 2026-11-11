package com.flowcortex.workflow.domain;

import com.flowcortex.workflow.api.WorkflowVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A workflow definition. {@link WorkflowVisibility} is the sharing dimension: a public definition
 * is listed to every authenticated user and an unlisted one is readable through its key, but
 * neither implies shared RUNS — {@link WorkflowRun#ownerId} always scopes reads to the user who
 * started it. {@code is_shared} mirrors {@code visibility == PUBLIC} so a rollback keeps working.
 */
@Entity
@Table(name = "workflow")
public class Workflow {

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

    private Workflow(UUID id, UUID ownerId, String name, boolean shared, WorkflowStatus status) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.shared = shared;
        this.visibility = shared ? WorkflowVisibility.PUBLIC : WorkflowVisibility.PRIVATE;
        this.status = status;
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

    public void changeStatus(WorkflowStatus status) {
        this.status = status;
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
}
