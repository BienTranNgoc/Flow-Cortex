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
 * One step's execution within a {@link WorkflowRun}. The status ladder is enforced in the
 * mutators — {@link #markRunning}, {@link #recordPickup}, {@link #extendLease}, {@link
 * #markCompleted}, {@link #markError} — every other call throws {@link IllegalStateException}.
 *
 * <p>The mutators model the transitions for direct testing; the live engine applies each one as
 * an atomic row-count {@code UPDATE ... WHERE status = ... AND attempt = ...} ({@code
 * RunStepRepository}) — those statements are the real guard against two executors, a late
 * result or a reaper racing the same step, not these in-memory checks.
 *
 * <p>A {@code running} step with no {@code pickedUpAt} is still waiting in the queue; once
 * picked up it is held by {@code leaseUntil} (extended by heartbeats) and {@code runDeadline}.
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

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "progress_percent")
    private Short progressPercent;

    @Column(name = "attempt", nullable = false)
    private short attempt = 1;

    @Column(name = "error_code")
    private String errorCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outputs")
    private String outputs;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "queue_deadline")
    private Instant queueDeadline;

    @Column(name = "run_deadline")
    private Instant runDeadline;

    @Column(name = "delivery_count", nullable = false)
    private short deliveryCount;

    /** Required by JPA. */
    protected RunStep() {
    }

    public RunStep(UUID id, UUID runId, short stepOrder, String type) {
        this(id, runId, stepOrder, type, null);
    }

    /** A pending step whose input is fixed when its run starts rather than resolved at dispatch. */
    public RunStep(UUID id, UUID runId, short stepOrder, String type, String input) {
        this.id = id;
        this.runId = runId;
        this.stepOrder = stepOrder;
        this.type = type;
        this.input = input;
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

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Short getProgressPercent() {
        return progressPercent;
    }

    public short getAttempt() {
        return attempt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** The result's objects as JSON ({@code [{name, bucket, objectKey, mime, bytes, meta}]}), or {@code null}. */
    public String getOutputs() {
        return outputs;
    }

    public Instant getPickedUpAt() {
        return pickedUpAt;
    }

    public Instant getQueueDeadline() {
        return queueDeadline;
    }

    public Instant getRunDeadline() {
        return runDeadline;
    }

    public short getDeliveryCount() {
        return deliveryCount;
    }

    /** {@code pending -> running}. */
    public void markRunning(Instant now) {
        markRunning(now, null);
    }

    /** {@code pending -> running}, waiting for pickup until {@code queueDeadline}. */
    public void markRunning(Instant now, Instant queueDeadline) {
        requireStatus(RunStepStatus.PENDING, RunStepStatus.RUNNING);
        this.status = RunStepStatus.RUNNING;
        this.startedAt = now;
        this.queueDeadline = queueDeadline;
    }

    /**
     * An executor picked the step up (once per delivery): the first pickup time is kept, the
     * run deadline restarts unless an older delivery arrives late; the lease never moves back.
     */
    public void recordPickup(Instant at, Instant leaseUntil, Instant runDeadline, short deliveryCount) {
        requireStatus(RunStepStatus.RUNNING, RunStepStatus.RUNNING);
        if (this.pickedUpAt == null) {
            this.pickedUpAt = at;
        }
        this.leaseUntil = later(this.leaseUntil, leaseUntil);
        if (deliveryCount >= this.deliveryCount) {
            this.runDeadline = runDeadline;
            this.deliveryCount = deliveryCount;
        }
    }

    /**
     * A heartbeat: extends the lease and records progress. A heartbeat that overtakes the
     * {@code started} message counts as pickup, and sets the run deadline only if none is set.
     */
    public void extendLease(Instant now, Instant leaseUntil, Short percent, short deliveryCount,
            Instant runDeadlineIfUnset) {
        requireStatus(RunStepStatus.RUNNING, RunStepStatus.RUNNING);
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new IllegalArgumentException("progress percent must be 0..100, was " + percent);
        }
        this.leaseUntil = later(this.leaseUntil, leaseUntil);
        if (percent != null) {
            this.progressPercent = percent;
        }
        this.deliveryCount = (short) Math.max(this.deliveryCount, deliveryCount);
        if (this.pickedUpAt == null) {
            this.pickedUpAt = now;
        }
        if (this.runDeadline == null) {
            this.runDeadline = runDeadlineIfUnset;
        }
    }

    /**
     * {@code running -> completed}. {@code resolvedInput} is recorded here, not at {@link
     * #markRunning}, since it's only known once input resolution succeeds.
     */
    public void markCompleted(String resolvedInput, String output, Instant now) {
        markCompleted(resolvedInput, output, now, null);
    }

    /** {@code running -> completed} with the result's objects ({@link #getOutputs()}). */
    public void markCompleted(String resolvedInput, String output, Instant now, String outputs) {
        requireStatus(RunStepStatus.RUNNING, RunStepStatus.COMPLETED);
        this.status = RunStepStatus.COMPLETED;
        this.input = resolvedInput;
        this.output = output;
        this.outputs = outputs;
        this.finishedAt = now;
    }

    /**
     * {@code running -> error} — halts the owning run. {@code resolvedInput} may be {@code
     * null}: an unresolved/dangling {@code input_ref} fails before any input value exists.
     */
    public void markError(String resolvedInput, String errorMessage, Instant now) {
        markError(resolvedInput, null, errorMessage, now);
    }

    /** {@code running -> error} with a machine-readable {@code errorCode} (nullable for engine errors). */
    public void markError(String resolvedInput, String errorCode, String errorMessage, Instant now) {
        requireStatus(RunStepStatus.RUNNING, RunStepStatus.ERROR);
        this.status = RunStepStatus.ERROR;
        this.input = resolvedInput;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }

    private static Instant later(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private void requireStatus(RunStepStatus required, RunStepStatus target) {
        if (status != required) {
            throw new IllegalStateException(
                    "Illegal run_step transition: cannot move to " + target + " from " + status
                            + " (required " + required + ")");
        }
    }
}
