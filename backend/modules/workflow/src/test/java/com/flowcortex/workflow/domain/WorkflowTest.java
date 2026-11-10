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

    @Test
    void aNewDraftAndAForkStartWithForkingOff() {
        assertThat(new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Demo", true).isAllowFork()).isFalse();
        Workflow draft = Workflow.draft(UUID.randomUUID(), UUID.randomUUID(), "Demo", true);
        assertThat(draft.isAllowFork()).isFalse();
        assertThat(draft.getForkCount()).isZero();
        assertThat(draft.getForkedFromId()).isNull();
    }

    @Test
    void forkOfRecordsTheSourceAndIsAnUnsharedDraft() {
        UUID source = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        Workflow fork = Workflow.forkOf(UUID.randomUUID(), owner, "Copy", source);

        assertThat(fork.getForkedFromId()).isEqualTo(source);
        assertThat(fork.getOwnerId()).isEqualTo(owner);
        assertThat(fork.getName()).isEqualTo("Copy");
        assertThat(fork.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(fork.isShared()).isFalse();
        assertThat(fork.isAllowFork()).isFalse();
        assertThat(fork.getForkCount()).isZero();
    }
}
