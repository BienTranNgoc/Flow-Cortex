package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A workflow definition (design.md D1). {@code isShared} is the ONLY sharing
 * dimension: a shared definition is listed to every authenticated user
 * (R-049), but that never implies shared RUNS — {@link WorkflowRun#ownerId}
 * always scopes reads to the user who started it.
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected Workflow() {
    }

    public Workflow(UUID id, UUID ownerId, String name, boolean shared) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.shared = shared;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
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
}
