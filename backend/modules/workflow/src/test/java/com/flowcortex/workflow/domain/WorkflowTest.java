package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The {@code workflow} entity. */
class WorkflowTest {

    @Test
    void theConstructorCreatesAPublishedWorkflowAndDraftCreatesADraft() {
        UUID owner = UUID.randomUUID();

        assertThat(new Workflow(UUID.randomUUID(), owner, "Demo", true).getStatus())
                .isEqualTo(WorkflowStatus.PUBLISHED);
        Workflow draft = Workflow.draft(UUID.randomUUID(), owner, "Demo", false);
        assertThat(draft.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(draft.getDeletedAt()).isNull();
    }

    @Test
    void mutatorsChangeTheFieldAndMarkDeletedStampsTheTime() throws InterruptedException {
        Workflow workflow = Workflow.draft(UUID.randomUUID(), UUID.randomUUID(), "Demo", false);
        Thread.sleep(2);

        workflow.rename("Renamed");
        workflow.setShared(true);
        workflow.changeStatus(WorkflowStatus.PUBLISHED);
        workflow.markDeleted();

        assertThat(workflow.getName()).isEqualTo("Renamed");
        assertThat(workflow.isShared()).isTrue();
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);
        assertThat(workflow.getDeletedAt()).isNotNull();
        assertThat(workflow.getUpdatedAt()).isAfter(workflow.getCreatedAt());
    }
}
