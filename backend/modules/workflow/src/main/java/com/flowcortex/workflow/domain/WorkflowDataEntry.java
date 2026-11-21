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
 * One named JSON value a {@link Workflow} keeps for its steps to read. {@code value} is the JSON text,
 * {@code sizeBytes} its compact UTF-8 length as measured by the application when it was written.
 */
@Entity
@Table(name = "workflow_data_store")
public class WorkflowDataEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "data_type", nullable = false)
    private String dataType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected WorkflowDataEntry() {
    }

    public WorkflowDataEntry(UUID id, UUID workflowId, String key, String dataType, String value, int sizeBytes,
            Instant now) {
        this.id = id;
        this.workflowId = workflowId;
        this.key = key;
        this.dataType = dataType;
        this.value = value;
        this.sizeBytes = sizeBytes;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replaces the value; a {@code null} {@code dataType} keeps the entry's current type. */
    public void replace(String value, String dataType, int sizeBytes, Instant now) {
        this.value = value;
        if (dataType != null) {
            this.dataType = dataType;
        }
        this.sizeBytes = sizeBytes;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public String getKey() {
        return key;
    }

    public String getDataType() {
        return dataType;
    }

    public String getValue() {
        return value;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
