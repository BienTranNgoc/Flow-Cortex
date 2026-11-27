package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored state of a member's edit of a step: {@code content} is a JSON object from pointer to the edited text
 * of each editable field. Immutable; the history of a step is capped by the service that writes it.
 */
@Entity
@Table(name = "run_step_output_version")
public class RunStepOutputVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_step_id", nullable = false, updatable = false)
    private UUID runStepId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected RunStepOutputVersion() {
    }

    public RunStepOutputVersion(UUID id, UUID runStepId, int version, String content, Instant createdAt) {
        this.id = id;
        this.runStepId = runStepId;
        this.version = version;
        this.content = content;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunStepId() {
        return runStepId;
    }

    public int getVersion() {
        return version;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
