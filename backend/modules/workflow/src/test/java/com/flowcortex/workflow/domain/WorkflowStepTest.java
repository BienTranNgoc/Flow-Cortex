package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The {@code workflow_step} entity. */
class WorkflowStepTest {

    @Test
    void editReplacesNameConfigAndInputRefButNeverTheTypeOrPosition() {
        WorkflowStep step = new WorkflowStep(UUID.randomUUID(), UUID.randomUUID(), (short) 2, "Old", "echo", null);

        step.edit("New", "{\"a\":1}", "{step:1.value}");

        assertThat(step.getName()).isEqualTo("New");
        assertThat(step.getConfig()).isEqualTo("{\"a\":1}");
        assertThat(step.getInputRef()).isEqualTo("{step:1.value}");
        assertThat(step.getType()).isEqualTo("echo");
        assertThat(step.getStepOrder()).isEqualTo((short) 2);
    }
}
