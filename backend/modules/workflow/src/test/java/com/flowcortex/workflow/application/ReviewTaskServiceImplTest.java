package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ReviewTaskView;
import com.flowcortex.workflow.api.ScriptRevisionView;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.ReviewTaskStatus;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.ScriptRevision;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.ScriptRevisionRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class ReviewTaskServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    private final ReviewTaskRepository reviewTaskRepository = mock(ReviewTaskRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final ScriptRevisionRepository scriptRevisionRepository = mock(ScriptRevisionRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ReviewTaskServiceImpl service = new ReviewTaskServiceImpl(reviewTaskRepository, runStepRepository,
            workflowRunRepository, scriptRevisionRepository, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID ownerId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), ownerId, "hello", NOW);
    private final ReviewTask task = new ReviewTask(UUID.randomUUID(), run.getId(), UUID.randomUUID(),
            "APPROVAL_CHECKPOINT", "hello", NOW);

    private void givenOwnedTask() {
        when(reviewTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(workflowRunRepository.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.of(run));
        when(workflowRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(reviewTaskRepository.save(task)).thenReturn(task);
    }

    /** A Product Video checkpoint arrives with revision 1 already attached to the task. */
    private ScriptRevision givenScriptTask() {
        givenOwnedTask();
        ScriptRevision revision = new ScriptRevision(UUID.randomUUID(), run.getId(), task.getId(), 1,
                "Kịch bản một", ownerId, NOW);
        task.attachRevision(revision.getId(), taskVersion());
        when(reviewTaskRepository.save(task)).thenAnswer(call -> {
            bumpVersion();
            return task;
        });
        return revision;
    }

    /**
     * The {@code @Version} counter is Hibernate's to advance, so an unmocked unit test starts at 0
     * and stays there. Every accepted write is bumped here to keep the optimistic guard's arithmetic
     * honest; the concurrency proof itself is {@code ApprovalRaceIT}.
     */
    private long taskVersion() {
        Long version = task.getVersion();
        return version == null ? 0L : version;
    }

    private void bumpVersion() {
        ReflectionTestUtils.setField(task, "version", taskVersion() + 1);
    }

    private void givenRevisionRepository() {
        when(scriptRevisionRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(reviewTaskRepository.saveAndFlush(task)).thenAnswer(call -> {
            bumpVersion();
            return task;
        });
    }

    @Test
    void approvingWithoutAPayloadPublishesTheOriginalPayloadForTheTasksRun() {
        givenOwnedTask();

        ReviewTaskView view = service.approveTask(task.getId(), ownerId, null, null, null);

        assertThat(view.status()).isEqualTo("approved");
        assertThat(view.reviewedAt()).isEqualTo(NOW);
        verify(events).publishEvent(new ReviewTaskApprovedEvent(task.getId(), run.getId(), "hello", null));
    }

    @Test
    void approvingAScriptPublishesTheApprovedRevisionId() {
        ScriptRevision revision = givenScriptTask();
        when(scriptRevisionRepository.findByIdAndReviewTaskId(revision.getId(), task.getId()))
                .thenReturn(Optional.of(revision));

        service.approveTask(task.getId(), ownerId, null, revision.getId(), taskVersion());

        verify(events).publishEvent(new ReviewTaskApprovedEvent(
                task.getId(), run.getId(), revision.getContent(), revision.getId()));
    }

    @Test
    void approvingWithABlankPayloadIsUnprocessableAndLeavesTheTaskPending() {
        givenOwnedTask();

        assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, "   ", null, null))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("Content can't be empty");
                    assertThat(e.getErrors()).containsKey("reviewedPayload");
                });
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(reviewTaskRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void aDecisionByAnotherMemberIsNotFoundAndLeavesTheTaskPending() {
        givenOwnedTask();
        UUID otherMember = UUID.randomUUID();

        assertThatThrownBy(() -> service.approveTask(task.getId(), otherMember, null, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Review task not found");
        assertThatThrownBy(() -> service.rejectTask(task.getId(), otherMember))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Review task not found");
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(reviewTaskRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void aDecisionOnAnUnknownTaskIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(reviewTaskRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveTask(unknown, ownerId, null, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Review task not found");
        assertThatThrownBy(() -> service.rejectTask(unknown, ownerId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Review task not found");
    }

    @Test
    void aDecisionOnAStoppingOrStoppedRunIsUnprocessable() {
        givenOwnedTask();
        for (RunStatus stopping : new RunStatus[] {RunStatus.CANCELLING, RunStatus.CANCELLED}) {
            ReflectionTestUtils.setField(run, "status", stopping);

            assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, null, null, null))
                    .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("This run is being stopped");
                        assertThat(e.getCode()).isEqualTo("RUN_STOPPING");
                    });
            assertThatThrownBy(() -> service.rejectTask(task.getId(), ownerId))
                    .isInstanceOfSatisfying(UnprocessableEntityException.class,
                            e -> assertThat(e.getCode()).isEqualTo("RUN_STOPPING"));
        }
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(runStepRepository, never()).failPendingApproval(any(), any(), anyString(), anyString(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void aDecisionOnATaskThatIsNoLongerPendingIsAConflictSayingSo() {
        givenOwnedTask();
        service.approveTask(task.getId(), ownerId, null, null, null);

        assertThatThrownBy(() -> service.rejectTask(task.getId(), ownerId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This step is no longer waiting for review");
    }

    @Test
    void losingTheVersionCheckToAConcurrentDecisionIsTheSameConflict() {
        givenOwnedTask();
        doThrow(new ObjectOptimisticLockingFailureException(ReviewTask.class, task.getId()))
                .when(reviewTaskRepository).flush();

        assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, null, null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This step is no longer waiting for review");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void rejectingWhenTheCheckpointStepIsNoLongerPendingIsAConflict() {
        givenOwnedTask();
        when(runStepRepository.failPendingApproval(eq(task.getRunStepId()), eq(run.getId()), anyString(), anyString(),
                eq(NOW))).thenReturn(0);

        assertThatThrownBy(() -> service.rejectTask(task.getId(), ownerId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This step is no longer waiting for review");
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

    @Test
    void savingARevisionStoresTrimmedContentAndAdvancesTheCurrentPointer() {
        givenScriptTask();
        givenRevisionRepository();
        when(scriptRevisionRepository.countByReviewTaskId(task.getId())).thenReturn(1L);

        ScriptRevisionView saved = service.saveRevision(task.getId(), ownerId, "  Kịch bản hai  ", 0L);

        assertThat(saved.revisionNumber()).isEqualTo(2);
        assertThat(saved.content()).isEqualTo("Kịch bản hai");
        assertThat(saved.id()).isEqualTo(task.getCurrentRevisionId());
        assertThat(saved.reviewTaskVersion()).isEqualTo(1L);
        verify(reviewTaskRepository).saveAndFlush(task);
    }

    @Test
    void aStaleExpectedVersionIsAConflict() {
        givenScriptTask();
        givenRevisionRepository();
        when(scriptRevisionRepository.countByReviewTaskId(task.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.saveRevision(task.getId(), ownerId, "Kịch bản ba", 99L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expected version 99");
    }

    @Test
    void savingARevisionOnATaskThatHasNoneIsAConflict() {
        givenOwnedTask();

        assertThatThrownBy(() -> service.saveRevision(task.getId(), ownerId, "Kịch bản", 0L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not use script revisions");
    }

    @Test
    void approvingAScriptWithoutRevisionIdAndExpectedVersionIsAConflict() {
        givenScriptTask();

        assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, "nội dung", null, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("revisionId and expectedVersion are required");
    }

    @Test
    void approvingAScriptWithReviewedPayloadIsAConflict() {
        ScriptRevision revision = givenScriptTask();

        assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, "changed inline",
                revision.getId(), taskVersion()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("reviewedPayload is not accepted");
    }

    @Test
    void approvingAPlainTaskWithRevisionFieldsIsAConflict() {
        givenOwnedTask();

        assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, null, UUID.randomUUID(), 0L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not accepted for plain approval");
    }

    @Test
    void approvingAnOlderRevisionOnceANewerOneExistsIsAConflict() {
        ScriptRevision first = givenScriptTask();
        givenRevisionRepository();
        when(scriptRevisionRepository.countByReviewTaskId(task.getId())).thenReturn(1L);
        ScriptRevisionView second = service.saveRevision(task.getId(), ownerId, "Kịch bản hai", 0L);
        when(scriptRevisionRepository.findByIdAndReviewTaskId(first.getId(), task.getId()))
                .thenReturn(Optional.of(first));

        assertThat(second.revisionNumber()).isEqualTo(2);
        assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, null, first.getId(), 1L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only the current script revision can be approved");
    }

    @Test
    void approvingARevisionOfAnotherTaskIsNotFound() {
        givenScriptTask();
        ScriptRevision foreign = new ScriptRevision(UUID.randomUUID(), run.getId(), UUID.randomUUID(), 1, "x",
                ownerId, NOW);
        when(scriptRevisionRepository.findByIdAndReviewTaskId(foreign.getId(), task.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, null, foreign.getId(), 1L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void readingRevisionsOutsideTheCallersRunIsNotFound() {
        when(reviewTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(workflowRunRepository.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRevisions(task.getId(), ownerId)).isInstanceOf(NotFoundException.class);
    }
}
