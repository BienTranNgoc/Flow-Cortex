package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Task 5.2 — the {@code workflow_step} entity (design.md D1, R-040/R-042). */
class WorkflowStepTest {

    @Test
    void constructorSetsAllFieldsIncludingOptionalInputRef() {
        UUID id = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        WorkflowStep step = new WorkflowStep(id, workflowId, (short) 2, "Extract field", "echo", "{step:1.value}");

        assertThat(step.getId()).isEqualTo(id);
        assertThat(step.getWorkflowId()).isEqualTo(workflowId);
        assertThat(step.getStepOrder()).isEqualTo((short) 2);
        assertThat(step.getName()).isEqualTo("Extract field");
        assertThat(step.getType()).isEqualTo("echo");
        assertThat(step.getInputRef()).isEqualTo("{step:1.value}");
        assertThat(step.getConfig()).isNull();
    }

    @Test
    void inputRefDefaultsToNull() {
        WorkflowStep step = new WorkflowStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "First", "echo", null);

        assertThat(step.getInputRef()).isNull();
    }
}
