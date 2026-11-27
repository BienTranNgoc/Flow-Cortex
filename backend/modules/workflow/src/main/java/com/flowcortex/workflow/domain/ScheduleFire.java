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
 * One attempt to start a run for a schedule: recorded {@code starting} before the run exists and
 * joined to the run afterwards by {@code requestKey}. Read-only: it is written by the statements of
 * {@code ScheduleFireRepository}, each guarded on the status it leaves.
 *
 * <p>{@code scheduleId} is {@code null} once the schedule is deleted; the fire stays in the history.
 */
@Entity
@Table(name = "workflow_schedule_fire")
public class ScheduleFire {

    public static final String MANUAL = "manual";
    public static final String SCHEDULED = "scheduled";

    public static final String STARTING = "starting";
    public static final String STARTED = "started";
    public static final String FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false, insertable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", nullable = false, insertable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "workflow_id", nullable = false, insertable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "schedule_id", insertable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "trigger", nullable = false, insertable = false, updatable = false)
    private String trigger;

    @Column(name = "scheduled_at", nullable = false, insertable = false, updatable = false)
    private Instant scheduledAt;

    @Column(name = "status", nullable = false, insertable = false, updatable = false)
    private String status;

    @Column(name = "request_key", nullable = false, insertable = false, updatable = false)
    private String requestKey;

    @Column(name = "error_code", insertable = false, updatable = false)
    private String errorCode;

    /** The message key of the refusal; its text is resolved in the reader's language when read. */
    @Column(name = "error_key", insertable = false, updatable = false)
    private String errorKey;

    /** The message arguments as a JSON array of strings and numbers. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_args", insertable = false, updatable = false)
    private String errorArgs;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected ScheduleFire() {
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

    public UUID getScheduleId() {
        return scheduleId;
    }

    public String getTrigger() {
        return trigger;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public String getStatus() {
        return status;
    }

    public String getRequestKey() {
        return requestKey;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorKey() {
        return errorKey;
    }

    public String getErrorArgs() {
        return errorArgs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
