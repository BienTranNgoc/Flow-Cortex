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
 * One execution of a {@link Workflow}. {@code status} is DERIVED from this
 * run's {@link RunStep}s — {@link #markCompleted}/{@link #markError} are
 * only ever called by the orchestrator (application layer), never directly
 * by a caller.
 *
 * <p>Always owner-scoped to the user who started it — never implied shared
 * by a shared workflow DEFINITION.
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

    /** The JSON options the run was started with; {@code null} for a run started before they existed. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", updatable = false)
    private String options;

    /** Required by JPA. */
    protected WorkflowRun() {
    }

    public WorkflowRun(UUID id, UUID workflowId, UUID ownerId, String input, Instant startedAt) {
        this(id, workflowId, ownerId, input, null, startedAt);
    }

    public WorkflowRun(UUID id, UUID workflowId, UUID ownerId, String input, String options, Instant startedAt) {
        this.id = id;
        this.workflowId = workflowId;
        this.ownerId = ownerId;
        this.input = input;
        this.options = options;
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

    public void markWaitingApproval() {
        this.status = RunStatus.WAITING_APPROVAL;
    }

    public void resume() {
        this.status = RunStatus.RUNNING;
        this.finishedAt = null;
    }

    public String getInput() {
        return input;
    }

    public String getOptions() {
        return options;
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

    /** Reached once every {@link RunStep} of this run is {@code COMPLETED}. */
    public void markCompleted(Instant now) {
        this.status = RunStatus.COMPLETED;
        this.finishedAt = now;
    }

    /** Reached the moment any {@link RunStep} errors — halts further dispatch. */
    public void markError(String errorMessage, Instant now) {
        this.status = RunStatus.ERROR;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }
}
