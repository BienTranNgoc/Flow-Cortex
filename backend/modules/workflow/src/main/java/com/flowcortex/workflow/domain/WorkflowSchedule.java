package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A member's one schedule for a workflow. Read-only: every write is a single statement in
 * {@code WorkflowScheduleRepository}, so the status, pause time and next fire change together
 * and two concurrent writers cannot interleave a load-modify-save.
 *
 * <p>{@code frequency} and {@code status} are the lowercase strings the table checks;
 * {@code dayOfWeek} is the ISO number (1 = Monday).
 */
@Entity
@Table(name = "workflow_schedule")
public class WorkflowSchedule {

    public static final String ACTIVE = "active";
    public static final String PAUSED = "paused";

    @Id
    @Column(name = "id", nullable = false, insertable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, insertable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "workflow_id", nullable = false, insertable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "frequency", nullable = false, insertable = false, updatable = false)
    private String frequency;

    @Column(name = "day_of_week", insertable = false, updatable = false)
    private Short dayOfWeek;

    @Column(name = "day_of_month", insertable = false, updatable = false)
    private Short dayOfMonth;

    @Column(name = "local_time", nullable = false, insertable = false, updatable = false)
    private LocalTime localTime;

    @Column(name = "time_zone", nullable = false, insertable = false, updatable = false)
    private String timeZone;

    @Column(name = "status", nullable = false, insertable = false, updatable = false)
    private String status;

    @Column(name = "paused_at", insertable = false, updatable = false)
    private Instant pausedAt;

    @Column(name = "next_run_at", insertable = false, updatable = false)
    private Instant nextRunAt;

    @Column(name = "input", insertable = false, updatable = false)
    private String input;

    @Column(name = "acknowledge_cost", nullable = false, insertable = false, updatable = false)
    private boolean acknowledgeCost;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected WorkflowSchedule() {
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

    public String getFrequency() {
        return frequency;
    }

    public Short getDayOfWeek() {
        return dayOfWeek;
    }

    public Short getDayOfMonth() {
        return dayOfMonth;
    }

    public LocalTime getLocalTime() {
        return localTime;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public String getInput() {
        return input;
    }

    public boolean isAcknowledgeCost() {
        return acknowledgeCost;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
