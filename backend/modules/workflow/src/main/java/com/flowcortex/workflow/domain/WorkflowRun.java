package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One execution of a {@link Workflow} (design.md D5). {@code status} is
 * DERIVED from this run's {@link RunStep}s (R-044) — {@link #markCompleted}/
 * {@link #markError} are only ever called by the orchestrator
 * (application layer), never directly by a caller.
 *
 * <p>Always owner-scoped to the user who started it (R-047) — never implied
 * shared by a shared workflow DEFINITION (design.md D1).
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "status", nullable = false)
    private RunStatus status;

    @Column(name = "input")
    private String input;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Required by JPA. */
    protected WorkflowRun() {
    }

    public WorkflowRun(UUID id, UUID workflowId, UUID ownerId, String input, Instant startedAt) {
        this.id = id;
        this.workflowId = workflowId;
        this.ownerId = ownerId;
        this.input = input;
        this.status = RunStatus.RUNNING;
        this.startedAt = startedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getInput() {
        return input;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    /** R-044: reached once every {@link RunStep} of this run is {@code COMPLETED}. */
    public void markCompleted(Instant now) {
        this.status = RunStatus.COMPLETED;
        this.finishedAt = now;
    }

    /** R-044: reached the moment any {@link RunStep} errors — halts further dispatch. */
    public void markError(String errorMessage, Instant now) {
        this.status = RunStatus.ERROR;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }
}
