package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * One input row of a {@link BatchRun} and the run it started. {@code starting} is internal: a claimed row whose
 * run start is not recorded yet, reported to members as {@code pending}. Only the new row is written through
 * this entity; every later change is a conditional update in {@code BatchRunRowRepository}.
 */
@Entity
@DynamicUpdate
@Table(name = "batch_run_row")
public class BatchRunRow {

    public static final String PENDING = "pending";
    public static final String STARTING = "starting";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "row_index", nullable = false, updatable = false)
    private int rowIndex;

    @Column(name = "source_row_index", updatable = false)
    private Integer sourceRowIndex;

    @Column(name = "input", nullable = false, updatable = false)
    private String input;

    @Column(name = "status", nullable = false, updatable = false)
    private String status;

    @Column(name = "run_id", updatable = false)
    private UUID runId;

    /** {@code short}: Hibernate's schema validation rejects an {@code Integer} on a {@code smallint} column. */
    @Column(name = "start_failures", nullable = false, updatable = false)
    private short startFailures;

    @Column(name = "stop_requested", nullable = false, updatable = false)
    private boolean stopRequested;

    @Column(name = "error_code", updatable = false)
    private String errorCode;

    @Column(name = "error_message", updatable = false)
    private String errorMessage;

    @Column(name = "claimed_at", updatable = false)
    private Instant claimedAt;

    @Column(name = "started_at", updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at", updatable = false)
    private Instant finishedAt;

    /** Required by JPA. */
    protected BatchRunRow() {
    }

    /** A new {@code pending} row; {@code sourceRowIndex} is set on a retry batch's rows only. */
    public BatchRunRow(UUID id, UUID batchId, int rowIndex, Integer sourceRowIndex, String input) {
        this.id = id;
        this.batchId = batchId;
        this.rowIndex = rowIndex;
        this.sourceRowIndex = sourceRowIndex;
        this.input = input;
        this.status = PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public Integer getSourceRowIndex() {
        return sourceRowIndex;
    }

    public String getInput() {
        return input;
    }

    public String getStatus() {
        return status;
    }

    public UUID getRunId() {
        return runId;
    }

    public short getStartFailures() {
        return startFailures;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
