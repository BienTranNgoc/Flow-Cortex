package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.workflow.api.ReviewTaskView;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ReviewTaskServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    private final ReviewTaskRepository reviewTaskRepository = mock(ReviewTaskRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ReviewTaskServiceImpl service = new ReviewTaskServiceImpl(reviewTaskRepository, runStepRepository,
            workflowRunRepository, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID ownerId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), ownerId, "hello", NOW);
    private final ReviewTask task = new ReviewTask(UUID.randomUUID(), run.getId(), UUID.randomUUID(),
            "APPROVAL_CHECKPOINT", "hello", NOW);

    private void givenOwnedTask() {
        when(reviewTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(workflowRunRepository.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.of(run));
        when(reviewTaskRepository.save(task)).thenReturn(task);
    }

    @Test
    void approvingWithABlankPayloadPublishesTheOriginalPayloadForTheTasksRun() {
        givenOwnedTask();

        ReviewTaskView view = service.approveTask(task.getId(), ownerId, " ");

        assertThat(view.status()).isEqualTo("approved");
        assertThat(view.reviewedAt()).isEqualTo(NOW);
        verify(events).publishEvent(new ReviewTaskApprovedEvent(task.getId(), run.getId(), "hello"));
    }

    @Test
    void rejectingWhenTheCheckpointStepIsNoLongerPendingIsAConflict() {
        givenOwnedTask();
        when(runStepRepository.failPendingApproval(eq(task.getRunStepId()), eq(run.getId()), anyString(), anyString(),
                eq(NOW))).thenReturn(0);

        assertThatThrownBy(() -> service.rejectTask(task.getId(), ownerId)).isInstanceOf(ConflictException.class);
        verify(workflowRunRepository, never()).save(any());
    }

    @Test
    void rejectingMarksTheCheckpointStepRejectedAndTheRunInError() {
        givenOwnedTask();
        when(runStepRepository.failPendingApproval(task.getRunStepId(), run.getId(), "APPROVAL_REJECTED",
                "Approval rejected", NOW)).thenReturn(1);

        ReviewTaskView view = service.rejectTask(task.getId(), ownerId);

        assertThat(view.status()).isEqualTo("rejected");
        assertThat(view.reviewedAt()).isEqualTo(NOW);
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        verify(workflowRunRepository).save(run);
    }
}
