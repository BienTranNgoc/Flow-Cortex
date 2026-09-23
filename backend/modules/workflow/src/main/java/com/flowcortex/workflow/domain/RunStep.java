package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One step's execution within a {@link WorkflowRun}. The status ladder is enforced in the
 * mutators — {@link #markRunning}, {@link #markCompleted}, {@link #markError} — every other
 * call throws {@link IllegalStateException}.
 *
 * <p>{@link #markRunning} models the transition for direct testing; the live dispatch path
 * instead claims a step via an atomic {@code UPDATE ... WHERE status = 'pending'} ({@code
 * RunStepRepository#claimPending}) — that SQL statement is the real guard against two
 * concurrent executions racing the same step, not this in-memory check.
 */
@Entity
@Table(name = "run_step")
public class RunStep {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "step_order", nullable = false, updatable = false)
    private short stepOrder;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "status", nullable = false)
    private RunStepStatus status;

    @Column(name = "input")
    private String input;

    @Column(name = "output")
    private String output;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Required by JPA. */
    protected RunStep() {
    }

    public RunStep(UUID id, UUID runId, short stepOrder, String type) {
        this.id = id;
        this.runId = runId;
        this.stepOrder = stepOrder;
        this.type = type;
        this.status = RunStepStatus.PENDING;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public short getStepOrder() {
        return stepOrder;
    }

    public String getType() {
        return type;
    }

    public RunStepStatus getStatus() {
        return status;
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
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

    /** {@code pending -> running}. */
    public void markRunning(Instant now) {
        requireStatus(RunStepStatus.PENDING, RunStepStatus.RUNNING);
        this.status = RunStepStatus.RUNNING;
        this.startedAt = now;
    }

    /**
     * {@code running -> completed}. {@code resolvedInput} is recorded here, not at {@link
     * #markRunning}, since it's only known once input resolution succeeds.
     */
    public void markCompleted(String resolvedInput, String output, Instant now) {
        requireStatus(RunStepStatus.RUNNING, RunStepStatus.COMPLETED);
        this.status = RunStepStatus.COMPLETED;
        this.input = resolvedInput;
        this.output = output;
        this.finishedAt = now;
    }

    /**
     * {@code running -> error} — halts the owning run. {@code resolvedInput} may be {@code
     * null}: an unresolved/dangling {@code input_ref} fails before any input value exists.
     */
    public void markError(String resolvedInput, String errorMessage, Instant now) {
        requireStatus(RunStepStatus.RUNNING, RunStepStatus.ERROR);
        this.status = RunStepStatus.ERROR;
        this.input = resolvedInput;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }

    private void requireStatus(RunStepStatus required, RunStepStatus target) {
        if (status != required) {
            throw new IllegalStateException(
                    "Illegal run_step transition: cannot move to " + target + " from " + status
                            + " (required " + required + ")");
        }
    }
}
