package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A member's batch of runs of one {@link Workflow}, one run per input row. Statuses are the lowercase
 * database values. Only the new batch is written through this entity: every later change is a conditional
 * update in {@code BatchRunRepository}, so a status column is never overwritten from a stale copy. The
 * {@code lease_token} and {@code lease_until} columns are not mapped: only those repository writes touch them.
 */
@Entity
@DynamicUpdate
@Table(name = "batch_run")
public class BatchRun {

    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String COMPLETED = "completed";
    public static final String COMPLETED_WITH_ERRORS = "completed_with_errors";
    public static final String CANCELLED = "cancelled";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "parent_batch_id", updatable = false)
    private UUID parentBatchId;

    @Column(name = "name", updatable = false)
    private String name;

    /** {@code short}: Hibernate's schema validation rejects an {@code Integer} on a {@code smallint} column. */
    @Column(name = "concurrency", nullable = false, updatable = false)
    private short concurrency;

    @Column(name = "acknowledge_cost", nullable = false, updatable = false)
    private boolean acknowledgeCost;

    @Column(name = "status", nullable = false, updatable = false)
    private String status;

    @Column(name = "pause_reason", updatable = false)
    private String pauseReason;

    @Column(name = "cancel_reason", updatable = false)
    private String cancelReason;

    @Column(name = "total_rows", nullable = false, updatable = false)
    private int totalRows;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at", updatable = false)
    private Instant finishedAt;

    @Column(name = "dispatched_at", updatable = false)
    private Instant dispatchedAt;

    /** Required by JPA. */
    protected BatchRun() {
    }

    /** A new {@code running} batch; {@code parentBatchId} is set on a retry batch only. */
    public BatchRun(UUID id, UUID ownerId, UUID workflowId, UUID parentBatchId, String name, short concurrency,
            boolean acknowledgeCost, int totalRows, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.workflowId = workflowId;
        this.parentBatchId = parentBatchId;
        this.name = name;
        this.concurrency = concurrency;
        this.acknowledgeCost = acknowledgeCost;
        this.status = RUNNING;
        this.totalRows = totalRows;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getParentBatchId() {
        return parentBatchId;
    }

    public String getName() {
        return name;
    }

    public short getConcurrency() {
        return concurrency;
    }

    public boolean isAcknowledgeCost() {
        return acknowledgeCost;
    }

    public String getStatus() {
        return status;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }
}
