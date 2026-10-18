package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ReviewTaskService;
import com.flowcortex.workflow.api.ReviewTaskView;
import com.flowcortex.workflow.api.ScriptRevisionView;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.ScriptRevision;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.ScriptRevisionRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A decision is guarded twice: the task must still be pending, and the task row's {@code
 * version} must not have moved since it was read — a concurrent decision on the same task fails
 * its flush with an optimistic-lock error. Either way the loser gets the same 409.
 */
@Service
@Transactional
class ReviewTaskServiceImpl implements ReviewTaskService {

    private static final String TASK_NOT_FOUND = "Review task not found";
    private static final String RUN_STOPPING = "RUN_STOPPING";
    private static final String RUN_STOPPING_DETAIL = "This run is being stopped";
    private static final String EMPTY_CONTENT = "Content can't be empty";

    private final ReviewTaskRepository reviewTaskRepository;
    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ScriptRevisionRepository scriptRevisionRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    ReviewTaskServiceImpl(
        ReviewTaskRepository reviewTaskRepository,
        RunStepRepository runStepRepository,
        WorkflowRunRepository workflowRunRepository,
        ScriptRevisionRepository scriptRevisionRepository,
        ApplicationEventPublisher events
    ) {
        this(reviewTaskRepository, runStepRepository, workflowRunRepository, scriptRevisionRepository,
                events, Clock.systemUTC());
    }

    ReviewTaskServiceImpl(
        ReviewTaskRepository reviewTaskRepository,
        RunStepRepository runStepRepository,
        WorkflowRunRepository workflowRunRepository,
        ScriptRevisionRepository scriptRevisionRepository,
        ApplicationEventPublisher events,
        Clock clock
    ) {
        this.reviewTaskRepository = reviewTaskRepository;
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.scriptRevisionRepository = scriptRevisionRepository;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewTaskView> getTasksByRunId(UUID runId, UUID ownerId) {
        // IDOR guard: ensure the run exists and belongs to the caller
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, ownerId)
            .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));

        return reviewTaskRepository.findByRunId(run.getId()).stream()
            .map(this::toView)
            .toList();
    }

    @Override
    public ReviewTaskView approveTask(UUID taskId, UUID reviewerId, String reviewedPayload,
            UUID revisionId, Long expectedVersion) {
        ReviewTask task = decidableTask(taskId, reviewerId).task();

        String payload;
        if (task.getCurrentRevisionId() != null) {
            if (revisionId == null || expectedVersion == null) {
                throw new ConflictException("revisionId and expectedVersion are required for script approval");
            }
            if (reviewedPayload != null) {
                throw new ConflictException("reviewedPayload is not accepted for script approval");
            }
            ScriptRevision revision = scriptRevisionRepository.findByIdAndReviewTaskId(revisionId, taskId)
                    .orElseThrow(() -> new NotFoundException("Script revision not found: " + revisionId));
            task.approveRevision(reviewerId, revisionId, expectedVersion, clock.instant());
            payload = revision.getContent();
        } else {
            if (revisionId != null || expectedVersion != null) {
                throw new ConflictException("revisionId and expectedVersion are not accepted for plain approval");
            }
            if (reviewedPayload != null && reviewedPayload.isBlank()) {
                throw new UnprocessableEntityException(EMPTY_CONTENT, null,
                        Map.of("reviewedPayload", EMPTY_CONTENT));
            }
            task.approve(reviewerId, reviewedPayload, clock.instant());
            payload = reviewedPayload != null ? reviewedPayload : task.getOriginalPayload();
        }
        ReviewTaskView view = toView(saveDecision(task));
        events.publishEvent(new ReviewTaskApprovedEvent(
                task.getId(), task.getRunId(), payload, task.getApprovedRevisionId()));
        return view;
    }

    @Override
    public ScriptRevisionView saveRevision(UUID taskId, UUID reviewerId, String content, long expectedVersion) {
        ReviewTask task = ownedTask(taskId, reviewerId);
        if (task.getCurrentRevisionId() == null) {
            throw new ConflictException("This review task does not use script revisions");
        }
        // The `@Version` guard in `attachRevision` runs inside this transaction, so a stale tab
        // rolls the insert back; the flush turns a concurrent writer's `revision_number` clash into
        // the same 409 rather than a 500.
        ScriptRevision revision = new ScriptRevision(UUID.randomUUID(), task.getRunId(), taskId,
                Math.toIntExact(scriptRevisionRepository.countByReviewTaskId(taskId) + 1),
                content.trim(), reviewerId, clock.instant());
        try {
            scriptRevisionRepository.saveAndFlush(revision);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Another revision was saved first; reload and try again");
        }
        task.attachRevision(revision.getId(), expectedVersion);
        reviewTaskRepository.saveAndFlush(task);
        return toView(revision, task.getVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScriptRevisionView> getRevisions(UUID taskId, UUID ownerId) {
        ownedTask(taskId, ownerId);
        return scriptRevisionRepository.findByReviewTaskIdOrderByRevisionNumberAsc(taskId).stream()
                .map(revision -> toView(revision, null)).toList();
    }

    @Override
    public ReviewTaskView rejectTask(UUID taskId, UUID reviewerId) {
        Decidable decidable = decidableTask(taskId, reviewerId);
        ReviewTask task = decidable.task();
        WorkflowRun run = decidable.run();

        Instant now = clock.instant();
        task.reject(reviewerId, now);
        ReviewTask saved = saveDecision(task);
        if (runStepRepository.failPendingApproval(task.getRunStepId(), task.getRunId(),
                "APPROVAL_REJECTED", "Approval rejected", now) != 1) {
            throw new ConflictException(ReviewTask.NOT_WAITING_FOR_REVIEW);
        }
        run.markError("Approval rejected", now);
        workflowRunRepository.save(run);
        return toView(saved);
    }

    private ReviewTaskView toView(ReviewTask task) {
        return new ReviewTaskView(
            task.getId(),
            task.getRunId(),
            task.getRunStepId(),
            task.getReviewType(),
            task.getStatus().name().toLowerCase(Locale.ROOT),
            task.getOriginalPayload(),
            task.getReviewedPayload(),
            task.getReviewedBy(),
            task.getReviewedAt(),
            task.getCreatedAt(),
            task.getVersion(),
            task.getCurrentRevisionId(),
            task.getApprovedRevisionId()
        );
    }

    private ScriptRevisionView toView(ScriptRevision revision, Long reviewTaskVersion) {
        return new ScriptRevisionView(revision.getId(), revision.getRunId(), revision.getReviewTaskId(),
                revision.getRevisionNumber(), revision.getContent(), revision.getCreatedBy(), revision.getCreatedAt(),
                reviewTaskVersion);
    }

    private ReviewTask ownedTask(UUID taskId, UUID ownerId) {
        ReviewTask task = reviewTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("ReviewTask not found: " + taskId));
        workflowRunRepository.findByIdAndOwnerId(task.getRunId(), ownerId)
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + task.getRunId()));
        return task;
    }

    /**
     * The task a decision may act on. Refuses, in this order, an unknown task or another member's
     * (the same not found, so a stranger learns nothing) and a run being stopped (unprocessable),
     * before anything is changed.
     */
    private Decidable decidableTask(UUID taskId, UUID reviewerId) {
        ReviewTask task = reviewTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException(TASK_NOT_FOUND));
        // Run row before step row, the order a cancel takes: a decision racing a stop waits for it
        // and then sees the stopped run, instead of deadlocking or approving a cancelled run.
        workflowRunRepository.lockById(task.getRunId());
        WorkflowRun run = workflowRunRepository.findById(task.getRunId())
                .orElseThrow(() -> new NotFoundException(TASK_NOT_FOUND));
        if (!run.getOwnerId().equals(reviewerId)) {
            throw new NotFoundException(TASK_NOT_FOUND);
        }
        if (run.getStatus() == RunStatus.CANCELLING || run.getStatus() == RunStatus.CANCELLED) {
            throw new UnprocessableEntityException(RUN_STOPPING_DETAIL, RUN_STOPPING);
        }
        return new Decidable(task, run);
    }

    /**
     * Flushes inside the transaction so a concurrent decision that committed first fails here, on
     * the task's version check, and answers the same 409 as a task already decided.
     */
    private ReviewTask saveDecision(ReviewTask task) {
        try {
            ReviewTask saved = reviewTaskRepository.save(task);
            reviewTaskRepository.flush();
            return saved;
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException(ReviewTask.NOT_WAITING_FOR_REVIEW);
        }
    }

    private record Decidable(ReviewTask task, WorkflowRun run) {
    }
}
