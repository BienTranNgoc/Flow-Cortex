package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import jakarta.validation.Validation;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** {@link WorkflowWriteAccess#requireOwned}. */
class WorkflowWriteAccessTest {

    private final WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
    private final WorkflowWriteAccess access = new WorkflowWriteAccess(workflowRepository,
            new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC()),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void theOwnerGetsTheWorkflow() {
        UUID ownerId = UUID.randomUUID();
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "W", false);
        when(workflowRepository.findVisibleTo(workflow.getId(), ownerId)).thenReturn(Optional.of(workflow));

        assertThat(access.requireOwned(Caller.member(ownerId), workflow.getId())).isSameAs(workflow);
    }

    @Test
    void aSharedPublishedWorkflowOfAnotherMemberIsForbidden() {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Workflow workflow = new Workflow(UUID.randomUUID(), ownerId, "W", true);
        when(workflowRepository.findVisibleTo(workflow.getId(), otherId)).thenReturn(Optional.of(workflow));

        assertThatThrownBy(() -> access.requireOwned(Caller.member(otherId), workflow.getId()))
                .isInstanceOfSatisfying(LocalizedStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void aPrivateWorkflowOfAnotherMemberIsNotFound() {
        UUID otherId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(workflowRepository.findVisibleTo(workflowId, otherId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireOwned(Caller.member(otherId), workflowId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aDeletedOrAbsentWorkflowIsNotFound() {
        UUID ownerId = UUID.randomUUID();
        Workflow deleted = new Workflow(UUID.randomUUID(), ownerId, "W", false);
        deleted.markDeleted();
        when(workflowRepository.findVisibleTo(deleted.getId(), ownerId)).thenReturn(Optional.empty());
        when(workflowRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> access.requireOwned(Caller.member(ownerId), deleted.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> access.requireOwned(Caller.admin(ownerId), deleted.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> access.requireOwned(Caller.member(ownerId), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theProductVideoWorkflowCanBeReadByItsOwner() {
        UUID ownerId = UUID.randomUUID();
        Workflow productVideo = new Workflow(ProductVideoWorkflow.ID, ownerId, "Product Video", true);
        when(workflowRepository.findVisibleTo(ProductVideoWorkflow.ID, ownerId)).thenReturn(Optional.of(productVideo));

        assertThat(access.requireOwned(Caller.member(ownerId), ProductVideoWorkflow.ID)).isSameAs(productVideo);
    }
}
