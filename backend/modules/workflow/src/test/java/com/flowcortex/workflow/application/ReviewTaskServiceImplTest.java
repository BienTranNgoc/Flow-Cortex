package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.EnglishBundle.english;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocaleConfig;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ReviewTaskRefusedException;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class ReviewTaskServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final Locale EN = Locale.ENGLISH;
    private static final Locale VI = Locale.of("vi");
    private static final MessageSource MESSAGES = new AnnotationConfigApplicationContext(LocaleConfig.class);

    private final ReviewTaskRepository reviewTaskRepository = mock(ReviewTaskRepository.class);
    private final RunStepRepository runStepRepository = mock(RunStepRepository.class);
    private final WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
    private final ScriptRevisionRepository scriptRevisionRepository = mock(ScriptRevisionRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final RunUpdates runUpdates = mock(RunUpdates.class);
    private final ReviewTaskServiceImpl service = new ReviewTaskServiceImpl(reviewTaskRepository, runStepRepository,
            workflowRunRepository, scriptRevisionRepository, events, runUpdates, MESSAGES,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID ownerId = UUID.randomUUID();
    private final WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), ownerId, "hello", NOW);
    private final ReviewTask task = new ReviewTask(UUID.randomUUID(), run.getId(), UUID.randomUUID(),
            "APPROVAL_CHECKPOINT", "hello", NOW, NOW.plus(Duration.ofDays(7)), null);

    private void givenOwnedTask() {
        givenOwned(task);
    }

    private void givenOwned(ReviewTask owned) {
        when(reviewTaskRepository.findById(owned.getId())).thenReturn(Optional.of(owned));
        when(reviewTaskRepository.findRunIdById(owned.getId())).thenReturn(Optional.of(owned.getRunId()));
        when(workflowRunRepository.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.of(run));
        when(workflowRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(reviewTaskRepository.save(owned)).thenReturn(owned);
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

    private ReviewTaskView approve(UUID taskId, UUID reviewerId, String payload, UUID revisionId, Long version) {
        return service.approveTask(taskId, reviewerId, payload, revisionId, version, null, null, EN);
    }

    private ReviewTaskView approveWithKey(String key) {
        return service.approveTask(task.getId(), ownerId, null, null, null, null, key, EN);
    }

    private ReviewTaskView reject(UUID taskId, UUID reviewerId) {
        return service.rejectTask(taskId, reviewerId, null, EN);
    }

    private ReviewTask qaTask(String questionsState, String questions) {
        return new ReviewTask(UUID.randomUUID(), run.getId(), UUID.randomUUID(), "QA_REVIEW", "Draft post", NOW,
                NOW.plus(Duration.ofDays(7)), questionsState, questions);
    }

    private ReviewTask optionsTask() {
        return new ReviewTask(UUID.randomUUID(), run.getId(), UUID.randomUUID(), "APPROVAL_CHECKPOINT",
                "[{\"hook\":\"A\"},{\"hook\":\"B\"}]", NOW, NOW.plus(Duration.ofDays(7)), null);
    }

    @Test
    void approvingWithoutAPayloadPublishesTheOriginalPayloadForTheTasksRun() {
        givenOwnedTask();

        ReviewTaskView view = approve(task.getId(), ownerId, null, null, null);

        assertThat(view.status()).isEqualTo("approved");
        assertThat(view.reviewedAt()).isEqualTo(NOW);
        verify(events).publishEvent(new ReviewTaskApprovedEvent(task.getId(), run.getId(), "hello", null));
    }

    @Test
    void approvingAScriptPublishesTheApprovedRevisionId() {
        ScriptRevision revision = givenScriptTask();
        when(scriptRevisionRepository.findByIdAndReviewTaskId(revision.getId(), task.getId()))
                .thenReturn(Optional.of(revision));

        approve(task.getId(), ownerId, null, revision.getId(), taskVersion());

        verify(events).publishEvent(new ReviewTaskApprovedEvent(
                task.getId(), run.getId(), revision.getContent(), revision.getId()));
    }

    @Test
    void approvingWithABlankPayloadIsUnprocessableAndLeavesTheTaskPending() {
        givenOwnedTask();

        assertThatThrownBy(() -> approve(task.getId(), ownerId, "   ", null, null))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(english(e.getDetail())).isEqualTo("Content can't be empty");
                    assertThat(english(e.getErrors().get("reviewedPayload"))).isEqualTo("Content can't be empty");
                });
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(reviewTaskRepository, never()).save(any());
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void aDecisionByAnotherMemberIsNotFoundAndLeavesTheTaskPending() {
        givenOwnedTask();
        UUID otherMember = UUID.randomUUID();

        assertThatThrownBy(() -> approve(task.getId(), otherMember, null, null, null))
                .isInstanceOf(NotFoundException.class)
                .satisfies(detail("Review task not found"));
        assertThatThrownBy(() -> reject(task.getId(), otherMember))
                .isInstanceOf(NotFoundException.class)
                .satisfies(detail("Review task not found"));
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(reviewTaskRepository, never()).save(any());
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void aDecisionOnAnUnknownTaskIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(reviewTaskRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> approve(unknown, ownerId, null, null, null))
                .isInstanceOf(NotFoundException.class)
                .satisfies(detail("Review task not found"));
        assertThatThrownBy(() -> reject(unknown, ownerId))
                .isInstanceOf(NotFoundException.class)
                .satisfies(detail("Review task not found"));
    }

    @Test
    void aDecisionOnAStoppingOrStoppedRunIsUnprocessable() {
        givenOwnedTask();
        for (RunStatus stopping : new RunStatus[] {RunStatus.CANCELLING, RunStatus.CANCELLED}) {
            ReflectionTestUtils.setField(run, "status", stopping);

            assertThatThrownBy(() -> approve(task.getId(), ownerId, null, null, null))
                    .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                        assertThat(english(e.getDetail())).isEqualTo("This run is being stopped");
                        assertThat(e.getCode()).isEqualTo("RUN_STOPPING");
                    });
            assertThatThrownBy(() -> reject(task.getId(), ownerId))
                    .isInstanceOfSatisfying(UnprocessableEntityException.class,
                            e -> assertThat(e.getCode()).isEqualTo("RUN_STOPPING"));
        }
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(runStepRepository, never()).failPendingApproval(any(), any(), anyString(), anyString(), any());
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void aDecisionOnATaskThatIsNoLongerPendingIsAConflictSayingSo() {
        givenOwnedTask();
        approve(task.getId(), ownerId, null, null, null);

        assertThatThrownBy(() -> reject(task.getId(), ownerId))
                .isInstanceOf(ConflictException.class)
                .satisfies(detail("This step is no longer waiting for review"));
    }

    @Test
    void losingTheVersionCheckToAConcurrentDecisionIsTheSameConflict() {
        givenOwnedTask();
        doThrow(new ObjectOptimisticLockingFailureException(ReviewTask.class, task.getId()))
                .when(reviewTaskRepository).flush();

        assertThatThrownBy(() -> approve(task.getId(), ownerId, null, null, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(detail("This step is no longer waiting for review"));
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void rejectingWhenTheCheckpointStepIsNoLongerPendingIsAConflict() {
        givenOwnedTask();
        when(runStepRepository.failPendingApproval(eq(task.getRunStepId()), eq(run.getId()), anyString(), anyString(),
                eq(NOW))).thenReturn(0);

        assertThatThrownBy(() -> reject(task.getId(), ownerId))
                .isInstanceOf(ConflictException.class)
                .satisfies(detail("This step is no longer waiting for review"));
        verify(workflowRunRepository, never()).save(any());
        verify(runUpdates, never()).touched(any());
    }

    @Test
    void rejectingMarksTheCheckpointStepRejectedAndTheRunInError() {
        givenOwnedTask();
        when(runStepRepository.failPendingApproval(task.getRunStepId(), run.getId(), "APPROVAL_REJECTED",
                "Approval rejected", NOW)).thenReturn(1);

        ReviewTaskView view = reject(task.getId(), ownerId);

        assertThat(view.status()).isEqualTo("rejected");
        assertThat(view.reviewedAt()).isEqualTo(NOW);
        assertThat(run.getStatus().name()).isEqualTo("ERROR");
        verify(workflowRunRepository).save(run);
        verify(runUpdates).touched(run.getId());
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
                .satisfies(detailContaining("expected version 99"));
    }

    @Test
    void savingARevisionOnATaskThatHasNoneIsAConflict() {
        givenOwnedTask();

        assertThatThrownBy(() -> service.saveRevision(task.getId(), ownerId, "Kịch bản", 0L))
                .isInstanceOf(ConflictException.class)
                .satisfies(detailContaining("does not use script revisions"));
    }

    @Test
    void approvingAScriptWithoutRevisionIdAndExpectedVersionIsAConflict() {
        givenScriptTask();

        assertThatThrownBy(() -> approve(task.getId(), ownerId, "nội dung", null, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(detailContaining("revisionId and expectedVersion are required"));
    }

    @Test
    void approvingAScriptWithReviewedPayloadIsAConflict() {
        ScriptRevision revision = givenScriptTask();

        assertThatThrownBy(() -> approve(task.getId(), ownerId, "changed inline",
                revision.getId(), taskVersion()))
                .isInstanceOf(ConflictException.class)
                .satisfies(detailContaining("reviewedPayload is not accepted"));
    }

    @Test
    void approvingAPlainTaskWithRevisionFieldsIsAConflict() {
        givenOwnedTask();

        assertThatThrownBy(() -> approve(task.getId(), ownerId, null, UUID.randomUUID(), 0L))
                .isInstanceOf(ConflictException.class)
                .satisfies(detailContaining("not accepted for plain approval"));
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
        assertThatThrownBy(() -> approve(task.getId(), ownerId, null, first.getId(), 1L))
                .isInstanceOf(ConflictException.class)
                .satisfies(detailContaining("Only the current script revision can be approved"));
    }

    @Test
    void approvingARevisionOfAnotherTaskIsNotFound() {
        givenScriptTask();
        ScriptRevision foreign = new ScriptRevision(UUID.randomUUID(), run.getId(), UUID.randomUUID(), 1, "x",
                ownerId, NOW);
        when(scriptRevisionRepository.findByIdAndReviewTaskId(foreign.getId(), task.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> approve(task.getId(), ownerId, null, foreign.getId(), 1L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void readingRevisionsOutsideTheCallersRunIsNotFound() {
        when(reviewTaskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(workflowRunRepository.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRevisions(task.getId(), ownerId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void approvingAPickedOptionPublishesThatOptionAsJson() {
        ReviewTask options = optionsTask();
        givenOwned(options);

        ReviewTaskView view = service.approveTask(options.getId(), ownerId, null, null, null, 1, null, EN);

        assertThat(view.status()).isEqualTo("approved");
        assertThat(view.reviewedPayload()).isEqualTo("{\"hook\":\"B\"}");
        verify(events).publishEvent(new ReviewTaskApprovedEvent(options.getId(), run.getId(), "{\"hook\":\"B\"}", null));
    }

    @Test
    void aPickedOptionOutOfRangeOrWithoutOptionsOrWithAPayloadIs422() {
        ReviewTask options = optionsTask();
        givenOwned(options);
        UUID id = options.getId();
        assertThatThrownBy(() -> service.approveTask(id, ownerId, null, null, null, 2, null, EN))
                .satisfies(refusedPickedOption());
        assertThatThrownBy(() -> service.approveTask(id, ownerId, null, null, null, -1, null, EN))
                .satisfies(refusedPickedOption());
        assertThatThrownBy(() -> service.approveTask(id, ownerId, "own", null, null, 0, null, EN))
                .satisfies(refusedPickedOption());
        assertThatThrownBy(() -> service.approveTask(id, ownerId, null, UUID.randomUUID(), 0L, 0, null, EN))
                .satisfies(refusedPickedOption());
        assertThatThrownBy(() -> service.approveTask(id, ownerId, null, null, 0L, 0, null, EN))
                .satisfies(refusedPickedOption());

        givenOwnedTask();
        assertThatThrownBy(() -> service.approveTask(task.getId(), ownerId, null, null, null, 0, null, EN))
                .satisfies(refusedPickedOption());

        assertThat(options.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        assertThat(task.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(reviewTaskRepository, never()).save(any());
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void aDecisionAfterExpiresAtIsAConflictReviewExpired() {
        ReviewTask late = new ReviewTask(UUID.randomUUID(), run.getId(), UUID.randomUUID(), "APPROVAL_CHECKPOINT",
                "hello", NOW.minus(Duration.ofDays(8)), NOW.minusSeconds(60), null);
        givenOwned(late);
        UUID id = late.getId();

        assertThatThrownBy(() -> service.approveTask(id, ownerId, null, null, null, null, null, EN))
                .satisfies(expired());
        assertThatThrownBy(() -> service.rejectTask(id, ownerId, null, EN)).satisfies(expired());
        assertThatThrownBy(() -> service.answerTask(id, ownerId, Map.of(), null, EN)).satisfies(expired());
        ReflectionTestUtils.setField(late, "expiresAt", NOW);
        assertThatThrownBy(() -> service.approveTask(id, ownerId, null, null, null, null, null, EN))
                .satisfies(expired());

        assertThat(late.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        ReflectionTestUtils.setField(late, "status", ReviewTaskStatus.EXPIRED);
        ReflectionTestUtils.setField(late, "expiresAt", NOW.plusSeconds(3600));
        assertThatThrownBy(() -> service.rejectTask(id, ownerId, "k", EN)).satisfies(expired());
        verify(reviewTaskRepository, never()).save(any());
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void aRepeatWithTheSameKeyReturnsTheTaskAndPublishesNothing() {
        givenOwnedTask();

        ReviewTaskView first = approveWithKey("k-1");
        ReviewTaskView repeat = approveWithKey("k-1");

        assertThat(first.status()).isEqualTo("approved");
        assertThat(repeat.status()).isEqualTo("approved");
        assertThat(repeat.reviewedAt()).isEqualTo(NOW);
        verify(events, times(1)).publishEvent(any(ReviewTaskApprovedEvent.class));
        assertThatThrownBy(() -> approveWithKey("k-2")).isInstanceOf(ConflictException.class)
                .satisfies(detail("This step is no longer waiting for review"));
        assertThatThrownBy(() -> approveWithKey(null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.rejectTask(task.getId(), ownerId, "k-1", EN))
                .isInstanceOf(ConflictException.class)
                .satisfies(detail("This step is no longer waiting for review"));
        verify(events, times(1)).publishEvent(any(ReviewTaskApprovedEvent.class));
        verify(runUpdates, never()).touched(any());
    }

    /**
     * The run lock is what serialises two decisions on one task, so the task must first be read
     * after it: a same-key repeat that waited for the first decision then sees it and replays,
     * instead of judging a pending snapshot and losing on the version check.
     */
    @Test
    void aDecisionReadsTheTaskOnlyAfterLockingItsRun() {
        givenOwnedTask();

        approveWithKey("k-1");
        ReviewTaskView repeat = approveWithKey("k-1");

        assertThat(repeat.status()).isEqualTo("approved");
        InOrder order = inOrder(workflowRunRepository, reviewTaskRepository);
        order.verify(workflowRunRepository).lockById(run.getId());
        order.verify(reviewTaskRepository).findById(task.getId());
        order.verify(workflowRunRepository).lockById(run.getId());
        order.verify(reviewTaskRepository).findById(task.getId());
    }

    @Test
    void answeringAReadyQaTaskApprovesWithTheCompiledPayload() {
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_READY, QaQuestions.STANDARD_MARKER);
        givenOwned(qa);

        ReviewTaskView view = service.answerTask(qa.getId(), ownerId, Map.of("q1", "Just me"), "k-1", EN);

        String compiled = "Draft post\n\n---\n## Human review\n\n**Who is the audience for this output?**\nJust me\n\n"
                + "**How detailed should the output be?**\n(no answer)\n\n"
                + "**Any specific requirements or constraints? (optional)**\n(no answer)\n\n";
        assertThat(view.status()).isEqualTo("approved");
        assertThat(view.reviewedPayload()).isEqualTo(compiled);
        assertThat(view.answers()).isEqualTo(Map.of("q1", "Just me"));
        verify(events).publishEvent(new ReviewTaskApprovedEvent(qa.getId(), run.getId(), compiled, null));

        ReviewTaskView repeat = service.answerTask(qa.getId(), ownerId, Map.of("q1", "Just me"), "k-1", EN);
        assertThat(repeat.status()).isEqualTo("approved");
        verify(events, times(1)).publishEvent(any(ReviewTaskApprovedEvent.class));
        assertThatThrownBy(() -> service.approveTask(qa.getId(), ownerId, null, null, null, null, "k-1", EN))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void answeringInVietnameseWritesVietnameseLabelsAndAcceptsTheOtherLanguagesOption() {
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_READY, QaQuestions.STANDARD_MARKER);
        givenOwned(qa);

        ReviewTaskView view = service.answerTask(qa.getId(), ownerId, Map.of("q1", "Just me"), null, VI);

        assertThat(view.reviewedPayload()).startsWith("Draft post\n\n---\n## Người duyệt trả lời\n\n"
                + "**Đối tượng nhận kết quả này là ai?**\nJust me\n\n");
        assertThat(view.reviewedPayload()).contains("(không trả lời)");
    }

    @Test
    void aStandardQuestionRefusesAnotherQuestionsOptionInEitherLanguage() {
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_READY, QaQuestions.STANDARD_MARKER);
        givenOwned(qa);

        for (String foreign : List.of("Brief key points", "Ngắn gọn, điểm chính")) {
            assertThatThrownBy(() -> service.answerTask(qa.getId(), ownerId, Map.of("q1", foreign), null, EN))
                    .as(foreign)
                    .isInstanceOfSatisfying(UnprocessableEntityException.class,
                            e -> assertThat(e.getErrors()).containsOnlyKeys("answers.q1"));
        }
        assertThat(qa.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void aStandardQuestionStillAcceptsItsOwnOptionInTheOtherLanguage() {
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_READY, QaQuestions.STANDARD_MARKER);
        givenOwned(qa);

        ReviewTaskView view = service.answerTask(qa.getId(), ownerId,
                Map.of("q1", "Cá nhân tôi", "q2", "Brief key points"), null, EN);

        assertThat(view.status()).isEqualTo("approved");
    }

    @Test
    void answeringWhileGeneratingIs409NotReady() {
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_GENERATING, null);
        givenOwned(qa);

        assertThatThrownBy(() -> service.answerTask(qa.getId(), ownerId, Map.of("q1", "Just me"), null, EN))
                .isInstanceOfSatisfying(ReviewTaskRefusedException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("REVIEW_QUESTIONS_NOT_READY");
                });
        assertThat(qa.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(reviewTaskRepository, never()).save(any());
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void answeringAPlainCheckpointIs422NotQa() {
        givenOwnedTask();

        assertThatThrownBy(() -> service.answerTask(task.getId(), ownerId, Map.of("q1", "Just me"), null, EN))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REVIEW_NOT_QA");
                    assertThat(english(e.getDetail())).isEqualTo("This review has no questions to answer");
                });
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void answeringWithAnUnknownQuestionIs422NamingIt() {
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_READY, QaQuestions.STANDARD_MARKER);
        givenOwned(qa);

        assertThatThrownBy(() -> service.answerTask(qa.getId(), ownerId,
                Map.of("q1", "Everyone", "zz", "x"), null, EN))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getErrors()).containsOnlyKeys("answers.q1", "answers.zz");
                    assertThat(english(e.getErrors().get("answers.q1"))).isEqualTo("Choose one of the offered options");
                    assertThat(english(e.getErrors().get("answers.zz")))
                            .isEqualTo("This question is not part of the review");
                });
        assertThat(qa.getStatus()).isEqualTo(ReviewTaskStatus.PENDING);
        verify(reviewTaskRepository, never()).save(any());
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));
    }

    @Test
    void answersToModelQuestionsAreCheckedAgainstThoseQuestions() {
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_READY,
                "[{\"id\":\"tone\",\"type\":\"multi\",\"question\":\"Which tone?\",\"options\":[\"Warm\",\"Dry\"]}]");
        givenOwned(qa);

        assertThatThrownBy(() -> service.answerTask(qa.getId(), ownerId,
                Map.of("tone", List.of("Warm", "Loud")), null, EN))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("answers.tone"));
        assertThatThrownBy(() -> service.answerTask(qa.getId(), ownerId, Map.of("q1", "Just me"), null, EN))
                .isInstanceOfSatisfying(UnprocessableEntityException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("answers.q1"));

        ReviewTaskView view = service.answerTask(qa.getId(), ownerId, Map.of("tone", List.of("Warm")), null, EN);

        assertThat(view.reviewedPayload())
                .isEqualTo("Draft post\n\n---\n## Human review\n\n**Which tone?**\n- Warm\n\n");
    }

    @Test
    void rejectingAQaTaskStopsTheRun() {
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_READY, QaQuestions.STANDARD_MARKER);
        givenOwned(qa);
        when(runStepRepository.failPendingApproval(qa.getRunStepId(), run.getId(), "APPROVAL_REJECTED",
                "Approval rejected", NOW)).thenReturn(1);

        ReviewTaskView view = service.rejectTask(qa.getId(), ownerId, "k-1", EN);

        assertThat(view.status()).isEqualTo("rejected");
        assertThat(run.getStatus()).isEqualTo(RunStatus.ERROR);
        verify(runUpdates).touched(run.getId());
        verify(events, never()).publishEvent(any(ReviewTaskApprovedEvent.class));

        ReviewTaskView repeat = service.rejectTask(qa.getId(), ownerId, "k-1", EN);
        assertThat(repeat.status()).isEqualTo("rejected");
        verify(runUpdates, times(1)).touched(run.getId());
    }

    @Test
    void theViewCarriesTheDeadlineThePickListAndTheQuestionsInTheRequestLanguage() {
        ReviewTask options = optionsTask();
        ReviewTask qa = qaTask(ReviewTask.QUESTIONS_READY, QaQuestions.STANDARD_MARKER);
        when(workflowRunRepository.findByIdAndOwnerId(run.getId(), ownerId)).thenReturn(Optional.of(run));
        when(reviewTaskRepository.findByRunId(run.getId())).thenReturn(List.of(options, qa));

        List<ReviewTaskView> english = service.getTasksByRunId(run.getId(), ownerId, EN);
        List<ReviewTaskView> vietnamese = service.getTasksByRunId(run.getId(), ownerId, VI);

        assertThat(english.get(0).expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(english.get(0).pickOptions()).containsExactly(Map.of("hook", "A"), Map.of("hook", "B"));
        assertThat(english.get(0).questions()).isNull();
        assertThat(english.get(0).questionsState()).isNull();
        assertThat(english.get(1).pickOptions()).isNull();
        assertThat(english.get(1).questionsState()).isEqualTo("ready");
        assertThat(english.get(1).questions()).extracting(ReviewTaskView.Question::question).containsExactly(
                "Who is the audience for this output?", "How detailed should the output be?",
                "Any specific requirements or constraints? (optional)");
        assertThat(english.get(1).questions().get(0).options()).hasSize(4);
        assertThat(english.get(1).questions().get(2).type()).isEqualTo("text");
        assertThat(vietnamese.get(1).questions().get(0).question()).isEqualTo("Đối tượng nhận kết quả này là ai?");
        assertThat(vietnamese.get(1).questions().get(0).options()).hasSize(4).first().isEqualTo("Cá nhân tôi");
    }

    private static ThrowingConsumer<Throwable> expired() {
        return e -> assertThat(e).isInstanceOfSatisfying(ReviewTaskRefusedException.class, refused -> {
            assertThat(refused.getStatusCode().value()).isEqualTo(409);
            assertThat(refused.code()).isEqualTo("REVIEW_EXPIRED");
            assertThat(english(refused.detail())).isEqualTo("This review expired, so the run stopped.");
        });
    }

    private static ThrowingConsumer<Throwable> refusedPickedOption() {
        return e -> assertThat(e).isInstanceOfSatisfying(UnprocessableEntityException.class,
                refused -> assertThat(refused.getErrors()).containsOnlyKeys("pickedOption"));
    }

    private static ThrowingConsumer<Throwable> detailContaining(String text) {
        return e -> assertThat(english(((ConflictException) e).getDetail())).contains(text);
    }

    private static ThrowingConsumer<Throwable> detail(String text) {
        return e -> assertThat(english(switch (e) {
            case NotFoundException notFound -> notFound.getDetail();
            case ConflictException conflict -> conflict.getDetail();
            default -> throw new AssertionError("No localized detail on " + e);
        })).isEqualTo(text);
    }
}
