package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.NewWorkflowStep;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard.StepDef;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.application.ImportGrading.Issue;
import com.flowcortex.workflow.application.ImportGrading.Review;
import com.flowcortex.workflow.application.OutboundTargets.Target;
import com.flowcortex.workflow.application.ReviewTokenState.ReviewRow;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowImportReview;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.WorkflowImportReviewRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a workflow definition between environments: an admin exports a live workflow, reviews a file (graded and
 * stored under a single-use token), and imports the reviewed file as a draft of their own.
 */
@Service
public class WorkflowTransferService {

    /** The largest artifact the review accepts and the export produces, in bytes. */
    public static final int MAX_ARTIFACT_BYTES = 2 * 1024 * 1024;

    static final int MAX_OPEN_REVIEWS = 5;
    static final Duration REVIEW_TTL = Duration.ofMinutes(15);
    /** Fault paths named in one 422, so a file of thousands of bad steps cannot make a huge answer. */
    static final int MAX_REPORTED_FAULTS = 100;

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowTransferService.class);
    private static final String REVIEW_LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))";
    private static final String REVIEW_LOCK_PREFIX = "workflow-import-review:";
    private static final Pattern DEFINITION_PATH =
            Pattern.compile("^steps(?:\\[(\\d+)]\\.(\\w+)|\\.(\\w+)\\[(\\d+)])$");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The artifact and the name its download carries. */
    public record Exported(ObjectNode artifact, String filename) {
    }

    /** What a review answers; {@code message} of each issue is resolved in the caller's language by the web layer. */
    public record ReviewView(UUID token, Instant expiresAt, String workflowName, int stepCount, boolean blocked,
            boolean outbound, List<IssueView> issues, List<TargetView> outboundTargets) {
    }

    /** {@code severity} is {@code blocking} or {@code advisory}; {@code stepOrder} and {@code field} may be null. */
    public record IssueView(String code, String severity, Integer stepOrder, String field, LocalizedMessage message) {
    }

    public record TargetView(int stepOrder, String type, String field, String value) {
    }

    /** {@code created} is false when the token had already produced {@code view}. */
    public record ImportResult(WorkflowView view, boolean created) {
    }

    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final WorkflowImportReviewRepository reviewRepository;
    private final WorkflowWriteAccess writeAccess;
    private final WorkflowReadinessGuard readinessGuard;
    private final StepExecutorRegistry registry;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Map<String, List<String>> outboundTable;

    @Autowired
    WorkflowTransferService(WorkflowRepository workflowRepository, WorkflowStepRepository stepRepository,
            WorkflowImportReviewRepository reviewRepository, WorkflowWriteAccess writeAccess,
            WorkflowReadinessGuard readinessGuard, StepExecutorRegistry registry, JdbcTemplate jdbc, Clock clock) {
        this(workflowRepository, stepRepository, reviewRepository, writeAccess, readinessGuard, registry, jdbc, clock,
                OutboundTargets.TABLE);
    }

    WorkflowTransferService(WorkflowRepository workflowRepository, WorkflowStepRepository stepRepository,
            WorkflowImportReviewRepository reviewRepository, WorkflowWriteAccess writeAccess,
            WorkflowReadinessGuard readinessGuard, StepExecutorRegistry registry, JdbcTemplate jdbc, Clock clock,
            Map<String, List<String>> outboundTable) {
        this.workflowRepository = workflowRepository;
        this.stepRepository = stepRepository;
        this.reviewRepository = reviewRepository;
        this.writeAccess = writeAccess;
        this.readinessGuard = readinessGuard;
        this.registry = registry;
        this.jdbc = jdbc;
        this.clock = clock;
        this.outboundTable = outboundTable;
    }

    /** The artifact of the live definition of any workflow but Product Video; the caller has verified the ADMIN role. */
    @Transactional(readOnly = true)
    public Exported export(UUID adminId, UUID workflowId) {
        Workflow workflow = workflowRepository.findActiveById(workflowId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.notFound", workflowId)));
        if (ProductVideoWorkflow.ID.equals(workflow.getId())) {
            throw new ConflictException(new LocalizedMessage("workflow.copy.fixedWorkflow"));
        }
        List<StepDefinition> steps = WorkflowCarry.of(stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId));
        ObjectNode artifact = WorkflowArtifacts.write(workflow.getName(), steps, clock.instant());
        if (artifact.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ARTIFACT_BYTES) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.artifact.tooLarge"),
                    "ARTIFACT_TOO_LARGE");
        }
        LOG.info("Workflow {} exported by admin {}", workflowId, adminId);
        return new Exported(artifact, WorkflowArtifacts.filename(workflow.getName()) + ".workflow.json");
    }

    /**
     * Parses and grades the file, then stores it under a new token valid for 15 minutes. The per-admin lock is taken
     * before the open reviews are counted and held to commit, so concurrent reviews cannot pass the cap together.
     */
    @Transactional
    public ReviewView review(UUID adminId, byte[] body) {
        if (body.length > MAX_ARTIFACT_BYTES) {
            throw new LocalizedStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    new LocalizedMessage("workflow.artifact.tooLarge"));
        }
        WorkflowArtifact artifact = parse(body);
        Review graded = grade(artifact);

        jdbc.query(REVIEW_LOCK, (RowCallbackHandler) row -> {
        }, REVIEW_LOCK_PREFIX + adminId);
        Instant now = clock.instant();
        if (reviewRepository.countByUserIdAndWorkflowIdIsNullAndExpiresAtAfter(adminId, now) >= MAX_OPEN_REVIEWS) {
            throw new LocalizedStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    new LocalizedMessage("workflow.artifact.tooManyReviews"));
        }
        WorkflowImportReview row = reviewRepository.saveAndFlush(new WorkflowImportReview(UUID.randomUUID(), adminId,
                WorkflowArtifacts.write(artifact.name(), artifact.steps(), artifact.exportedAt()).toString(),
                reviewJson(graded), graded.outbound(), body.length, now, now.plus(REVIEW_TTL)));
        return new ReviewView(row.getToken(), row.getExpiresAt(), artifact.name(), artifact.steps().size(),
                graded.blocked(), graded.outbound(), graded.issues().stream().map(WorkflowTransferService::view).toList(),
                graded.targets().stream().map(WorkflowTransferService::view).toList());
    }

    /**
     * Creates the reviewed workflow as a draft of the caller, once per token: the token row is locked, so a concurrent
     * second import waits and then answers with the workflow the first one created.
     */
    @Transactional
    public ImportResult importReviewed(UUID adminId, String token, Boolean acknowledgeOutboundTargets) {
        Optional<UUID> id = parseToken(token);
        Optional<WorkflowImportReview> found = id.flatMap(reviewRepository::findLockedByToken);
        Instant now = clock.instant();
        ReviewTokenState.State state = ReviewTokenState.of(
                found.map(row -> new ReviewRow(row.getUserId(), row.getExpiresAt(), row.getWorkflowId())), adminId,
                now);
        return switch (state) {
            case ReviewTokenState.Expired expired -> throw tokenExpired();
            case ReviewTokenState.Replay replay -> replay(replay.workflowId());
            case ReviewTokenState.Usable usable ->
                    create(found.orElseThrow(), adminId, Boolean.TRUE.equals(acknowledgeOutboundTargets), now);
        };
    }

    private ImportResult replay(UUID workflowId) {
        Workflow workflow = workflowRepository.findActiveById(workflowId).orElseThrow(WorkflowTransferService::tokenExpired);
        return new ImportResult(view(workflow, stepRepository.findByWorkflowIdOrderByStepOrderAsc(workflowId)), false);
    }

    private ImportResult create(WorkflowImportReview row, UUID adminId, boolean acknowledged, Instant now) {
        WorkflowArtifact artifact = storedArtifact(row);
        Review graded = grade(artifact);
        if (graded.blocked()) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.import.blocked"), "IMPORT_BLOCKED",
                    blockingIssues(graded));
        }
        if (graded.outbound() && !acknowledged) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.import.ackRequired"),
                    "OUTBOUND_ACK_REQUIRED");
        }
        Workflow workflow = workflowRepository
                .save(Workflow.draft(UUID.randomUUID(), adminId, artifact.name(), false));
        List<WorkflowStep> steps = stepRepository.saveAll(WorkflowCarry.copySteps(artifact.steps(), workflow.getId()));
        row.markImported(workflow.getId(), now);
        reviewRepository.save(row);
        LOG.info("Workflow {} imported by admin {} from review {}", workflow.getId(), adminId, row.getToken());
        return new ImportResult(view(workflow, steps), true);
    }

    private WorkflowView view(Workflow workflow, List<WorkflowStep> steps) {
        return WorkflowMapper.toWorkflowView(workflow, steps, registry::isRemote, registry::isAvailable);
    }

    private static WorkflowArtifact parse(byte[] body) {
        return switch (WorkflowArtifacts.read(body)) {
            case WorkflowArtifacts.Parsed parsed -> parsed.artifact();
            case WorkflowArtifacts.Invalid invalid -> {
                Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
                Map<String, String> reasons = new LinkedHashMap<>();
                new TreeMap<>(invalid.faults()).entrySet().stream().limit(MAX_REPORTED_FAULTS).forEach(fault -> {
                    errors.put(fault.getKey(), reasonMessage(fault.getValue()));
                    reasons.put(fault.getKey(), fault.getValue());
                });
                throw new UnprocessableEntityException(new LocalizedMessage("workflow.artifact.invalid"),
                        "ARTIFACT_INVALID", errors, reasons);
            }
        };
    }

    /** One literal per reason, so the bundle parity check can see every key this class names. */
    static LocalizedMessage reasonMessage(String reason) {
        return switch (reason) {
            case WorkflowArtifacts.Reason.NOT_JSON -> new LocalizedMessage("workflow.artifact.reason.not_json");
            case WorkflowArtifacts.Reason.NOT_OBJECT -> new LocalizedMessage("workflow.artifact.reason.not_object");
            case WorkflowArtifacts.Reason.DUPLICATE_KEY ->
                    new LocalizedMessage("workflow.artifact.reason.duplicate_key");
            case WorkflowArtifacts.Reason.UNKNOWN_KEY -> new LocalizedMessage("workflow.artifact.reason.unknown_key");
            case WorkflowArtifacts.Reason.MISSING_KEY -> new LocalizedMessage("workflow.artifact.reason.missing_key");
            case WorkflowArtifacts.Reason.WRONG_TYPE -> new LocalizedMessage("workflow.artifact.reason.wrong_type");
            case WorkflowArtifacts.Reason.UNSUPPORTED_FORMAT_VERSION ->
                    new LocalizedMessage("workflow.artifact.reason.unsupported_format_version");
            case WorkflowArtifacts.Reason.NOT_A_TIMESTAMP ->
                    new LocalizedMessage("workflow.artifact.reason.not_a_timestamp");
            case WorkflowArtifacts.Reason.BLANK -> new LocalizedMessage("workflow.artifact.reason.blank");
            case WorkflowArtifacts.Reason.TOO_LONG -> new LocalizedMessage("workflow.artifact.reason.too_long");
            case WorkflowArtifacts.Reason.TOO_MANY_STEPS ->
                    new LocalizedMessage("workflow.artifact.reason.too_many_steps");
            case WorkflowArtifacts.Reason.NOT_POSITION -> new LocalizedMessage("workflow.artifact.reason.not_position");
            case WorkflowArtifacts.Reason.INVALID_TEXT -> new LocalizedMessage("workflow.artifact.reason.invalid_text");
            default -> throw new IllegalArgumentException("no message for artifact reason " + reason);
        };
    }

    /** The stored file was written by {@link WorkflowArtifacts#write}, so it always reads back. */
    private static WorkflowArtifact storedArtifact(WorkflowImportReview row) {
        return switch (WorkflowArtifacts.read(row.getArtifact().getBytes(StandardCharsets.UTF_8))) {
            case WorkflowArtifacts.Parsed parsed -> parsed.artifact();
            case WorkflowArtifacts.Invalid invalid -> throw new IllegalStateException(
                    "import review " + row.getToken() + " holds an artifact that no longer reads: " + invalid.faults());
        };
    }

    private Review grade(WorkflowArtifact artifact) {
        List<StepDefinition> steps = artifact.steps();
        List<StepDef> runSteps = steps.stream()
                .map(step -> new StepDef(step.stepOrder(), step.name(), step.type(), step.config(), step.inputRef()))
                .toList();
        return ImportGrading.grade(artifact, definitionFaults(steps), readinessGuard.faults(runSteps),
                OutboundTargets.targetsOf(steps, outboundTable));
    }

    /** What authoring would refuse for these steps, keyed {@code steps[i].field}. */
    private Map<String, String> definitionFaults(List<StepDefinition> steps) {
        List<NewWorkflowStep> definition = steps.stream()
                .map(step -> new NewWorkflowStep(step.name(), step.type(), step.inputRef(), step.config()))
                .toList();
        try {
            writeAccess.validateDefinition(definition);
            return Map.of();
        } catch (ConstraintViolationException e) {
            Map<String, String> faults = new LinkedHashMap<>();
            for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
                faults.put(definitionPath(violation.getPropertyPath().toString()), violation.getMessage());
            }
            return faults;
        }
    }

    private static String definitionPath(String path) {
        Matcher match = DEFINITION_PATH.matcher(path);
        if (!match.matches()) {
            return path;
        }
        return match.group(1) != null ? "steps[" + match.group(1) + "]." + match.group(2)
                : "steps[" + match.group(4) + "]." + match.group(3);
    }

    private static Map<String, LocalizedMessage> blockingIssues(Review graded) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        graded.issues().stream()
                .filter(issue -> issue.severity() == ImportGrading.Severity.BLOCKING)
                .forEach(issue -> errors.put(issueKey(issue), issue.message()));
        return errors;
    }

    private static String issueKey(Issue issue) {
        String step = issue.stepOrder() == null ? "workflow" : "step" + issue.stepOrder();
        return issue.field() == null ? step : step + "." + issue.field();
    }

    private static String reviewJson(Review graded) {
        ObjectNode review = JSON.createObjectNode();
        ArrayNode issues = review.putArray("issues");
        for (Issue issue : graded.issues()) {
            ObjectNode node = issues.addObject();
            node.put("code", issue.code());
            node.put("severity", issue.severity().name().toLowerCase(Locale.ROOT));
            node.put("stepOrder", issue.stepOrder());
            node.put("field", issue.field());
        }
        ArrayNode targets = review.putArray("outboundTargets");
        for (Target target : graded.targets()) {
            ObjectNode node = targets.addObject();
            node.put("stepOrder", target.stepOrder());
            node.put("type", target.type());
            node.put("field", target.field());
            node.put("value", target.value());
        }
        try {
            return JSON.writeValueAsString(review);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a review tree always serializes", e);
        }
    }

    private static IssueView view(Issue issue) {
        return new IssueView(issue.code(), issue.severity().name().toLowerCase(Locale.ROOT),
                issue.stepOrder(), issue.field(), issue.message());
    }

    private static TargetView view(Target target) {
        return new TargetView(target.stepOrder(), target.type(), target.field(), target.value());
    }

    private static Optional<UUID> parseToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(token));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static UnprocessableEntityException tokenExpired() {
        return new UnprocessableEntityException(new LocalizedMessage("workflow.import.tokenExpired"),
                "IMPORT_TOKEN_EXPIRED");
    }
}
