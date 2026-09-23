package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The {@code workflow} entity. */
class WorkflowTest {

    @Test
    void constructorSetsAllFieldsAndTimestamps() {
        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        Workflow workflow = new Workflow(id, ownerId, "Demo", true);

        assertThat(workflow.getId()).isEqualTo(id);
        assertThat(workflow.getOwnerId()).isEqualTo(ownerId);
        assertThat(workflow.getName()).isEqualTo("Demo");
        assertThat(workflow.isShared()).isTrue();
        assertThat(workflow.getCreatedAt()).isNotNull();
        assertThat(workflow.getUpdatedAt()).isEqualTo(workflow.getCreatedAt());
    }

    @Test
    void notSharedByDefaultArgument() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Private", false);

        assertThat(workflow.isShared()).isFalse();
    }
}
