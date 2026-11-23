package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ReviewTaskRefusedException;
import com.flowcortex.workflow.api.ReviewTaskService;
import com.flowcortex.workflow.api.ReviewTaskView;
import com.flowcortex.workflow.api.ScriptRevisionView;
import com.flowcortex.workflow.application.QaQuestions.QaQuestion;
import com.flowcortex.workflow.application.ReviewDecisionReplay.Kind;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A decision is guarded twice: the task must still be pending, and the task row's {@code
 * version} must not have moved since it was read — a concurrent decision on the same task, the
 * expiry sweep or the questions being stored fails its flush with an optimistic-lock error. Either
 * way the loser gets the same 409.
 */
@Service
@Transactional
class ReviewTaskServiceImpl implements ReviewTaskService {

    private static final LocalizedMessage TASK_NOT_FOUND = new LocalizedMessage("reviewTask.decision.notFound");
    private static final String RUN_STOPPING = "RUN_STOPPING";
    private static final LocalizedMessage RUN_STOPPING_DETAIL = new LocalizedMessage("workflow.run.stopping");
    private static final LocalizedMessage EMPTY_CONTENT = new LocalizedMessage("reviewTask.emptyContent");
    private static final LocalizedMessage PICKED_OPTION_INVALID = new LocalizedMessage("reviewTask.pickedOption.invalid");
    private static final LocalizedMessage NOT_QA = new LocalizedMessage("reviewTask.notQa");
    private static final LocalizedMessage VALIDATION_FAILED = new LocalizedMessage("problem.validationFailed");
    private static final String QA_REVIEW = HumanSteps.reviewType(HumanSteps.QA_REVIEW);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final ReviewTaskRepository reviewTaskRepository;
    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ScriptRevisionRepository scriptRevisionRepository;
    private final ApplicationEventPublisher events;
    private final RunUpdates runUpdates;
    private final MessageSource messages;
    private final Clock clock;

    @Autowired
    ReviewTaskServiceImpl(
        ReviewTaskRepository reviewTaskRepository,
        RunStepRepository runStepRepository,
        WorkflowRunRepository workflowRunRepository,
        ScriptRevisionRepository scriptRevisionRepository,
        ApplicationEventPublisher events,
        RunUpdates runUpdates,
        MessageSource messages
    ) {
        this(reviewTaskRepository, runStepRepository, workflowRunRepository, scriptRevisionRepository,
                events, runUpdates, messages, Clock.systemUTC());
    }

    ReviewTaskServiceImpl(
        ReviewTaskRepository reviewTaskRepository,
        RunStepRepository runStepRepository,
        WorkflowRunRepository workflowRunRepository,
        ScriptRevisionRepository scriptRevisionRepository,
        ApplicationEventPublisher events,
        RunUpdates runUpdates,
        MessageSource messages,
        Clock clock
    ) {
        this.runUpdates = runUpdates;
        this.reviewTaskRepository = reviewTaskRepository;
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.scriptRevisionRepository = scriptRevisionRepository;
        this.events = events;
        this.messages = messages;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewTaskView> getTasksByRunId(UUID runId, UUID ownerId, Locale locale) {
        // IDOR guard: ensure the run exists and belongs to the caller
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, ownerId)
            .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));

        return reviewTaskRepository.findByRunId(run.getId()).stream()
            .map(task -> toView(task, locale))
            .toList();
    }

    @Override
    public ReviewTaskView approveTask(UUID taskId, UUID reviewerId, String reviewedPayload,
            UUID revisionId, Long expectedVersion, Integer pickedOption, String decisionKey, Locale locale) {
        ReviewTask task = decidableTask(taskId, reviewerId).task();
        Optional<ReviewTaskView> settled = settled(task, Kind.APPROVE, decisionKey, locale);
        if (settled.isPresent()) {
            return settled.get();
        }

        Instant now = clock.instant();
        String payload;
        if (pickedOption != null) {
            payload = pickedPayload(task, pickedOption, reviewedPayload, revisionId, expectedVersion);
            task.approve(reviewerId, payload, decisionKey, now);
        } else if (task.getCurrentRevisionId() != null) {
            if (revisionId == null || expectedVersion == null) {
                throw new ConflictException(new LocalizedMessage("reviewTask.scriptApprovalNeedsRevision"));
            }
            if (reviewedPayload != null) {
                throw new ConflictException(new LocalizedMessage("reviewTask.scriptApprovalTakesNoPayload"));
            }
            ScriptRevision revision = scriptRevisionRepository.findByIdAndReviewTaskId(revisionId, taskId)
                    .orElseThrow(() -> new NotFoundException(new LocalizedMessage("reviewTask.revisionNotFound", revisionId)));
            task.approveRevision(reviewerId, revisionId, expectedVersion, decisionKey, now);
            payload = revision.getContent();
        } else {
            if (revisionId != null || expectedVersion != null) {
                throw new ConflictException(new LocalizedMessage("reviewTask.plainApprovalTakesNoRevision"));
            }
            if (reviewedPayload != null && reviewedPayload.isBlank()) {
                throw new UnprocessableEntityException(EMPTY_CONTENT, null,
                        Map.of("reviewedPayload", EMPTY_CONTENT));
            }
            task.approve(reviewerId, reviewedPayload, decisionKey, now);
            payload = reviewedPayload != null ? reviewedPayload : task.getOriginalPayload();
        }
        ReviewTaskView view = toView(saveDecision(task), locale);
        events.publishEvent(new ReviewTaskApprovedEvent(
                task.getId(), task.getRunId(), payload, task.getApprovedRevisionId()));
        return view;
    }

    @Override
    public ReviewTaskView answerTask(UUID taskId, UUID reviewerId, Map<String, Object> answers, String decisionKey,
            Locale locale) {
        ReviewTask task = decidableTask(taskId, reviewerId).task();
        Optional<ReviewTaskView> settled = settled(task, Kind.ANSWERS, decisionKey, locale);
        if (settled.isPresent()) {
            return settled.get();
        }
        if (!QA_REVIEW.equals(task.getReviewType())) {
            throw new UnprocessableEntityException(NOT_QA, "REVIEW_NOT_QA");
        }
        if (ReviewTask.QUESTIONS_GENERATING.equals(task.getQuestionsState())) {
            throw ReviewTaskRefusedException.questionsNotReady();
        }

        List<QaQuestion> questions = questionsOf(task, locale).orElseThrow(() -> new IllegalStateException(
                "QA review " + task.getId() + " is ready but holds no readable questions"));
        JsonNode given = MAPPER.valueToTree(answers == null ? Map.of() : answers);
        Map<String, Set<String>> otherLanguage = QaQuestions.isStandard(task.getQuestions())
                ? StandardQaQuestions.optionsInEveryLanguage(messages) : Map.of();
        Map<String, LocalizedMessage> faults = QaAnswers.validate(questions, given, otherLanguage);
        if (!faults.isEmpty()) {
            Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
            faults.forEach((id, fault) -> errors.put("answers." + id, fault));
            throw new UnprocessableEntityException(VALIDATION_FAILED, null, errors);
        }

        QaAnswers.Labels labels = new QaAnswers.Labels(
                messages.getMessage("qaReview.compile.header", null, locale),
                messages.getMessage("qaReview.compile.noAnswer", null, locale));
        String compiled = QaAnswers.compile(task.getOriginalPayload(), questions, given, labels);
        task.answer(reviewerId, json(given), compiled, decisionKey, clock.instant());
        ReviewTaskView view = toView(saveDecision(task), locale);
        events.publishEvent(new ReviewTaskApprovedEvent(task.getId(), task.getRunId(), compiled, null));
        return view;
    }

    @Override
    public ScriptRevisionView saveRevision(UUID taskId, UUID reviewerId, String content, long expectedVersion) {
        ReviewTask task = ownedTask(taskId, reviewerId);
        if (task.getCurrentRevisionId() == null) {
            throw new ConflictException(new LocalizedMessage("reviewTask.noScriptRevisions"));
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
            throw new ConflictException(new LocalizedMessage("reviewTask.revisionSavedFirst"));
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
    public ReviewTaskView rejectTask(UUID taskId, UUID reviewerId, String decisionKey, Locale locale) {
        Decidable decidable = decidableTask(taskId, reviewerId);
        ReviewTask task = decidable.task();
        WorkflowRun run = decidable.run();
        Optional<ReviewTaskView> settled = settled(task, Kind.REJECT, decisionKey, locale);
        if (settled.isPresent()) {
            return settled.get();
        }

        Instant now = clock.instant();
        task.reject(reviewerId, decisionKey, now);
        ReviewTask saved = saveDecision(task);
        if (runStepRepository.failPendingApproval(task.getRunStepId(), task.getRunId(),
                "APPROVAL_REJECTED", "Approval rejected", now) != 1) {
            throw new ConflictException(ReviewTask.NOT_WAITING_FOR_REVIEW);
        }
        run.markError("Approval rejected", now);
        workflowRunRepository.save(run);
        runUpdates.touched(run.getId());
        return toView(saved, locale);
    }

    /**
     * The option a pick forwards, as compact JSON. A pick sent together with edited text or a
     * script revision, a pick on content that is no list, and an index outside the list are all
     * the same 422 on {@code pickedOption}; this runs before the revision checks so those fields
     * never turn it into the 409 for plain approvals.
     */
    private String pickedPayload(ReviewTask task, int pickedOption, String reviewedPayload, UUID revisionId,
            Long expectedVersion) {
        List<JsonNode> options = task.getCurrentRevisionId() != null || reviewedPayload != null || revisionId != null
                || expectedVersion != null ? List.of()
                : PickOptions.detect(task.getOriginalPayload()).orElse(List.of());
        if (pickedOption < 0 || pickedOption >= options.size()) {
            throw new UnprocessableEntityException(PICKED_OPTION_INVALID, null,
                    Map.of("pickedOption", PICKED_OPTION_INVALID));
        }
        return PickOptions.forward(options, pickedOption);
    }

    /**
     * What an arriving decision does to a task already decided, expired or past its deadline: a
     * repeat with the deciding key answers the task as it is, any other repeat is the usual
     * conflict, a late decision is refused as expired. Empty when the decision applies.
     */
    private Optional<ReviewTaskView> settled(ReviewTask task, Kind kind, String decisionKey, Locale locale) {
        ReviewDecisionReplay.Verdict verdict = ReviewDecisionReplay.judge(task.getStatus(),
                ReviewDecisionReplay.recordedKind(task.getStatus(), task.getAnswers()), task.getDecisionKey(), kind,
                decisionKey, clock.instant(), task.getExpiresAt());
        return switch (verdict) {
            case APPLY -> Optional.empty();
            case REPLAY -> Optional.of(toView(task, locale));
            case CONFLICT -> throw new ConflictException(ReviewTask.NOT_WAITING_FOR_REVIEW);
            case EXPIRED -> throw ReviewTaskRefusedException.expired();
        };
    }

    private ReviewTaskView toView(ReviewTask task, Locale locale) {
        List<ReviewTaskView.Question> questions = questionsOf(task, locale)
                .map(list -> list.stream()
                        .map(q -> new ReviewTaskView.Question(q.id(), q.type().wire(), q.question(), q.options()))
                        .toList())
                .orElse(null);
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
            task.getApprovedRevisionId(),
            task.getExpiresAt(),
            pickOptionsOf(task),
            questions,
            task.getQuestionsState(),
            task.getAnswers() == null ? null : object(task.getAnswers())
        );
    }

    /** The options of a content that is a list; none for a review that holds script revisions. */
    private List<Map<String, Object>> pickOptionsOf(ReviewTask task) {
        if (task.getCurrentRevisionId() != null) {
            return null;
        }
        return PickOptions.detect(task.getOriginalPayload())
                .map(options -> options.stream().map(option -> MAPPER.convertValue(option, JSON_OBJECT)).toList())
                .orElse(null);
    }

    /** The questions a stored review shows: the standard ones in the reader's language or the model's list. */
    private Optional<List<QaQuestion>> questionsOf(ReviewTask task, Locale locale) {
        if (task.getQuestions() == null) {
            return Optional.empty();
        }
        return QaQuestions.isStandard(task.getQuestions())
                ? Optional.of(StandardQaQuestions.in(messages, locale))
                : QaQuestions.parse(task.getQuestions());
    }

    private static Map<String, Object> object(String json) {
        try {
            return MAPPER.readValue(json, JSON_OBJECT);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A stored review answer is not a JSON object", e);
        }
    }

    private static String json(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The answers could not be written as JSON", e);
        }
    }

    private ScriptRevisionView toView(ScriptRevision revision, Long reviewTaskVersion) {
        return new ScriptRevisionView(revision.getId(), revision.getRunId(), revision.getReviewTaskId(),
                revision.getRevisionNumber(), revision.getContent(), revision.getCreatedBy(), revision.getCreatedAt(),
                reviewTaskVersion);
    }

    private ReviewTask ownedTask(UUID taskId, UUID ownerId) {
        ReviewTask task = reviewTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("reviewTask.notFound", taskId)));
        workflowRunRepository.findByIdAndOwnerId(task.getRunId(), ownerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", task.getRunId())));
        return task;
    }

    /**
     * The task a decision may act on. Refuses, in this order, an unknown task or another member's
     * (the same not found, so a stranger learns nothing) and a run being stopped (unprocessable),
     * before anything is changed.
     */
    private Decidable decidableTask(UUID taskId, UUID reviewerId) {
        UUID runId = reviewTaskRepository.findRunIdById(taskId)
                .orElseThrow(() -> new NotFoundException(TASK_NOT_FOUND));
        // Run row before step row, the order a cancel takes: a decision racing a stop waits for it
        // and then sees the stopped run, instead of deadlocking or approving a cancelled run. The
        // task is loaded only under the lock, so a decision that waited sees the one it waited for.
        workflowRunRepository.lockById(runId);
        ReviewTask task = reviewTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException(TASK_NOT_FOUND));
        WorkflowRun run = workflowRunRepository.findById(runId)
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
