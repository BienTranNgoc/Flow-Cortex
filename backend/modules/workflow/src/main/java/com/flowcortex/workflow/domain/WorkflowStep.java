package com.flowcortex.workflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One ordered step in a {@link Workflow} definition (design.md D1, R-040).
 * {@code inputRef}, when set, is the raw {@code {step:K.field}} reference
 * text (R-042) resolved at run time; {@code null} means "default: the
 * previous step's output" (or, for step 1, the run's own input). {@code
 * config} is reserved for a future step type's per-step settings — the MVP's
 * only types ({@code echo}/{@code fail}) need none (design.md Open
 * Questions).
 */
@Entity
@Table(name = "workflow_step")
public class WorkflowStep {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "step_order", nullable = false, updatable = false)
    private short stepOrder;

    @Column(name = "name")
    private String name;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config")
    private String config;

    @Column(name = "input_ref")
    private String inputRef;

    /** Required by JPA. */
    protected WorkflowStep() {
    }

    public WorkflowStep(UUID id, UUID workflowId, short stepOrder, String name, String type, String inputRef) {
        this.id = id;
        this.workflowId = workflowId;
        this.stepOrder = stepOrder;
        this.name = name;
        this.type = type;
        this.inputRef = inputRef;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public short getStepOrder() {
        return stepOrder;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getConfig() {
        return config;
    }

    public String getInputRef() {
        return inputRef;
    }
}
