package com.flowcortex.workflow.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.api.WorkflowVisibility;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The {@code workflow} entity. */
class WorkflowTest {

    @Test
    void theConstructorCreatesAPublishedWorkflowAndDraftCreatesADraft() {
        UUID owner = UUID.randomUUID();

        assertThat(new Workflow(UUID.randomUUID(), owner, "Demo", true).getStatus())
                .isEqualTo(WorkflowStatus.PUBLISHED);
        Workflow draft = Workflow.draft(UUID.randomUUID(), owner, "Demo");
        assertThat(draft.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(draft.getVisibility()).isEqualTo(WorkflowVisibility.PRIVATE);
        assertThat(draft.getDeletedAt()).isNull();
    }

    @Test
    void mutatorsChangeTheFieldAndMarkDeletedStampsTheTime() throws InterruptedException {
        Workflow workflow = Workflow.draft(UUID.randomUUID(), UUID.randomUUID(), "Demo");
        Thread.sleep(2);

        workflow.rename("Renamed");
        workflow.makeListedOrPrivate(WorkflowVisibility.PUBLIC);
        workflow.changeStatus(WorkflowStatus.PUBLISHED);
        workflow.markDeleted();

        assertThat(workflow.getName()).isEqualTo("Renamed");
        assertThat(workflow.getVisibility()).isEqualTo(WorkflowVisibility.PUBLIC);
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.PUBLISHED);
        assertThat(workflow.getDeletedAt()).isNotNull();
        assertThat(workflow.getUpdatedAt()).isAfter(workflow.getCreatedAt());
    }

    @Test
    void theSharedFlagMapsToPublicOrPrivateAndStaysInSyncWithEveryTierChange() {
        UUID owner = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-10T00:00:00Z");
        Instant expiry = at.plusSeconds(3600);

        assertThat(new Workflow(UUID.randomUUID(), owner, "A", true).getVisibility())
                .isEqualTo(WorkflowVisibility.PUBLIC);
        Workflow workflow = Workflow.draft(UUID.randomUUID(), owner, "B");
        assertThat(workflow.getVisibility()).isEqualTo(WorkflowVisibility.PRIVATE);

        workflow.makeUnlisted("key", at, expiry);
        assertThat(workflow.getVisibility()).isEqualTo(WorkflowVisibility.UNLISTED);
        assertThat(workflow.getUnlistedKey()).isEqualTo("key");
        assertThat(workflow.getUnlistedKeyGeneratedAt()).isEqualTo(at);
        assertThat(workflow.getUnlistedKeyExpiresAt()).isEqualTo(expiry);

        workflow.keepKey(null);
        assertThat(workflow.getUnlistedKey()).isEqualTo("key");
        assertThat(workflow.getUnlistedKeyExpiresAt()).isNull();

        workflow.rotateKey("other", at.plusSeconds(5));
        assertThat(workflow.getUnlistedKey()).isEqualTo("other");
        assertThat(workflow.getUnlistedKeyGeneratedAt()).isEqualTo(at.plusSeconds(5));

        workflow.makeListedOrPrivate(WorkflowVisibility.PUBLIC);
        assertThat(workflow.getVisibility()).isEqualTo(WorkflowVisibility.PUBLIC);
        assertThat(workflow.getUnlistedKey()).isNull();
        assertThat(workflow.getUnlistedKeyGeneratedAt()).isNull();
        assertThat(workflow.getUnlistedKeyExpiresAt()).isNull();

        assertThatThrownBy(() -> workflow.makeListedOrPrivate(WorkflowVisibility.UNLISTED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
