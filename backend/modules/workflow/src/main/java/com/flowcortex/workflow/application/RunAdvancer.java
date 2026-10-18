package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaJobCompleted;
import com.flowcortex.media.api.MediaJobProgressed;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaProgress;
import com.flowcortex.media.api.MediaResult;
import com.flowcortex.media.api.provider.ProviderScriptData;
import com.flowcortex.media.api.provider.ProviderSpeechData;
import com.flowcortex.media.api.provider.ProviderStoryboardData;
import com.flowcortex.media.api.provider.ProviderVideoData;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Moves a run forward one step per event — the run start, an inline step finishing, or a remote
 * result — never in a loop. {@link #advance} claims the lowest pending step and hands it on: an
 * inline step to Boot's {@code applicationTaskExecutor}, whose task records pickup, heartbeats
 * the lease every 30 s while it runs, then completes the step and advances again; a remote step
 * to the media engine inside the claim transaction, after which this returns at once and the step
 * moves only when {@link #onRemoteProgress} / {@link #onRemoteCompleted} events arrive.
 *
 * <p>A result is applied once: completion and failure are CAS on the step's status and attempt,
 * so a late or duplicate result is discarded (WARN + {@code media.late_result_discarded}) and
 * never reopens a step the reaper closed. A step whose task the executor rejects stays claimed
 * without pickup; {@link LeaseReaper} closes it at its queue deadline.
 *
 * <p>The listeners are {@code @ApplicationModuleListener}s: they run after the publishing
 * transaction commits, on an async thread, backed by the persistent event publication registry.
 */
@Component
class RunAdvancer {

    private static final Logger LOG = LoggerFactory.getLogger(RunAdvancer.class);
    /** Carried as W3C baggage on everything the run sends, so the worker logs it too. */
    private static final String BAGGAGE_RUN_ID = "runId";
    private static final String RUN_OBSERVATION = "workflow.run";
    private static final Duration HEARTBEAT = Duration.ofSeconds(30);
    /** Inline steps have no broker, so no redeliveries to count. */
    private static final short INLINE_DELIVERY = 0;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StoredWithOutputsResolver storedWithOutputsResolver = new StoredWithOutputsResolver();
    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final RunStepTransactions transactions;
    private final InputResolver inputResolver;
    private final StepExecutorRegistry registry;
    private final TaskExecutor taskExecutor;
    private final TaskScheduler scheduler;
    private final Tracer tracer;
    private final ObservationRegistry observations;
    private final Counter dispatchedJobs;
    private final Counter lateResults;
    private final Duration lease;
    private final Clock clock;
    private final ReviewCheckpointTransactions checkpointTransactions;
    private final MediaJobs mediaJobs;
    private final Validator validator;

    @Autowired
    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            WorkflowStepRepository workflowStepRepository, RunStepTransactions transactions,
            InputResolver inputResolver, StepExecutorRegistry registry,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor, TaskScheduler scheduler,
            ObjectProvider<Tracer> tracer, ObjectProvider<ObservationRegistry> observations, MeterRegistry meters,
            @Value("${flowcortex.workflow.lease-seconds}") long leaseSeconds,
            ReviewCheckpointTransactions checkpointTransactions, MediaJobs mediaJobs, Validator validator) {
        this(workflowRunRepository, runStepRepository, workflowStepRepository, transactions, inputResolver, registry,
                taskExecutor, scheduler, tracer.getIfAvailable(() -> Tracer.NOOP),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), meters, Duration.ofSeconds(leaseSeconds),
                Clock.systemUTC(), checkpointTransactions, mediaJobs, validator);
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            WorkflowStepRepository workflowStepRepository, RunStepTransactions transactions,
            InputResolver inputResolver, StepExecutorRegistry registry, TaskExecutor taskExecutor,
            TaskScheduler scheduler, Tracer tracer, ObservationRegistry observations, MeterRegistry meters,
            Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions, Validator validator) {
        this(workflowRunRepository, runStepRepository, workflowStepRepository, transactions, inputResolver, registry,
                taskExecutor, scheduler, tracer, observations, meters, lease, clock, checkpointTransactions, null,
                validator);
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            WorkflowStepRepository workflowStepRepository, RunStepTransactions transactions,
            InputResolver inputResolver, StepExecutorRegistry registry, TaskExecutor taskExecutor,
            TaskScheduler scheduler, Tracer tracer, ObservationRegistry observations, MeterRegistry meters,
            Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions, MediaJobs mediaJobs,
            Validator validator) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.transactions = transactions;
        this.inputResolver = inputResolver;
        this.registry = registry;
        this.taskExecutor = taskExecutor;
        this.scheduler = scheduler;
        this.tracer = tracer;
        this.observations = observations;
        this.dispatchedJobs = Counter.builder("media.jobs.dispatched")
                .description("Remote steps handed to the media engine")
                .register(meters);
        this.lateResults = Counter.builder("media.late_result_discarded")
                .description("Step results that lost the CAS: late after a timeout, or duplicates")
                .register(meters);
        this.lease = lease;
        this.clock = clock;
        this.checkpointTransactions = checkpointTransactions;
        this.mediaJobs = mediaJobs;
        this.validator = validator;
    }

    @ApplicationModuleListener
    void on(WorkflowRunStartedEvent event) {
        inRun(event.runId(), () -> advance(event.runId(), true));
    }

    @ApplicationModuleListener
    void onRemoteCompleted(MediaJobCompleted event) {
        MediaResult result = event.result();
        inRun(result.runId(), () -> {
            try (StepLogContext ignored = StepLogContext.step(result.runId(), result.jobId(), true, result.attempt(),
                    result.deliveryCount())) {
                applyResult(result);
            }
        });
    }

    @ApplicationModuleListener
    void onRemoteProgress(MediaJobProgressed event) {
        MediaProgress progress = event.progress();
        inRun(progress.runId(), () -> {
            try (StepLogContext ignored = StepLogContext.step(progress.runId(), progress.jobId(), true,
                    progress.attempt(), progress.deliveryCount())) {
                applyProgress(progress);
            }
        });
    }

    @ApplicationModuleListener
    void onReviewApproved(ReviewTaskApprovedEvent event) {
        if (checkpointTransactions.resume(event, clock.instant())) {
            advance(event.runId());
        }
    }

/**
     * Claims and hands on the run's next pending step; a no-op when the run is finished or a step is in flight.
     *
     * <p>Runs inside {@link RunStepTransactions#inNewTransaction}: a step transition is a bulk
     * {@code UPDATE} on its own transaction, so a {@link RunStep} loaded earlier in the caller's
     * transaction still carries its pre-transition status. Reading the ladder from that context
     * would report a closed step as still running and stall the run between steps, and the caller
     * here is this class itself, so the proxy a {@code @Transactional} here would need is skipped.
     */
    void advance(UUID runId) {
        advance(runId, false);
    }

    private void advance(UUID runId, boolean starting) {
        transactions.inNewTransaction(() -> inRun(runId, () -> advanceInRun(runId, starting)));
    }

    private void advanceInRun(UUID runId, boolean starting) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null) {
            LOG.warn("Workflow run {} not found — nothing to advance (should not happen)", runId);
            return;
        }
        if (run.getStatus() != RunStatus.RUNNING) {
            return;
        }

        List<RunStep> runSteps = runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        if (starting) {
            LOG.info("Workflow run started: runId={} steps={}", runId, runSteps.size());
        }
        Map<Integer, String> outputsByOrder = new LinkedHashMap<>();
        Map<Integer, String> assetOutputsByOrder = new LinkedHashMap<>();
        RunStep next = null;
        for (RunStep runStep : runSteps) {
            if (runStep.getStatus() != RunStepStatus.COMPLETED) {
                next = runStep;
                break;
            }
            outputsByOrder.put((int) runStep.getStepOrder(), runStep.getOutput());
            assetOutputsByOrder.put((int) runStep.getStepOrder(), runStep.getOutputs());
        }
        if (next == null) {
            transactions.completeRunIfDone(runId, clock.instant());
            return;
        }
        if (next.getStatus() != RunStepStatus.PENDING) {
            LOG.info("Step {} already {} — waiting for its result", next.getStepOrder(), next.getStatus());
            return;
        }
        if ("APPROVAL_CHECKPOINT".equals(next.getType())) {
            String payload = outputsByOrder.get((int) next.getStepOrder() - 1);
            checkpointTransactions.pause(run, next, payload, clock.instant());
            return;
        }
        dispatch(run, next, outputsByOrder, assetOutputsByOrder);
    }

    private void dispatch(WorkflowRun run, RunStep step, Map<Integer, String> outputsByOrder,
            Map<Integer, String> assetOutputsByOrder) {
        int order = step.getStepOrder();
        WorkflowStep definition = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(run.getWorkflowId())
                .stream()
                .collect(Collectors.toMap(s -> (int) s.getStepOrder(), s -> s))
                .get(order);
        Optional<StepExecutor> executor = registry.find(step.getType());
        boolean remote = executor.filter(e -> e.mode() == StepExecutor.Mode.REMOTE).isPresent();
        try (StepLogContext ignored = StepLogContext.step(run.getId(), step.getId(), remote, step.getAttempt(), null)) {
            dispatch(run, step, definition, executor, outputsByOrder, assetOutputsByOrder);
        }
    }

    private void dispatch(WorkflowRun run, RunStep step, WorkflowStep definition, Optional<StepExecutor> executor,
            Map<Integer, String> outputsByOrder, Map<Integer, String> assetOutputsByOrder) {
        int order = step.getStepOrder();
        String resolvedInput = null;
        Runnable inClaim;
        try {
            String stepConfig = definition == null ? null : definition.getConfig();
            StepExecutor.InputPolicy inputPolicy = executor.map(value -> value.inputPolicy(stepConfig))
                    .orElse(StepExecutor.InputPolicy.RESOLVED);
            resolvedInput = switch (inputPolicy) {
                case STORED -> step.getInput();
                case STORED_WITH_OUTPUTS -> storedWithOutputsResolver.resolve(step.getInput(),
                        stepConfig, assetOutputsByOrder, run.getId(), order);
                case RESOLVED -> inputResolver.resolve(order, definition == null ? null : definition.getInputRef(),
                        run.getInput(), outputsByOrder);
            };
            StepContext context = new StepContext(run.getId(), step.getId(), order, step.getType(), step.getAttempt(),
                    resolvedInput, step.getInput(), definition == null ? null : definition.getConfig());
            if (executor.isEmpty()) {
                inClaim = () -> {
                    throw new StepFailedException(null, "Unsupported step type: " + step.getType());
                };
            } else if (executor.get().mode() == StepExecutor.Mode.REMOTE) {
                inClaim = () -> executor.get().execute(context);
            } else {
                inClaim = () -> { };
            }
        } catch (IllegalStateException e) {
            inClaim = () -> {
                throw new StepFailedException(null, e.getMessage());
            };
        }

        Instant now = clock.instant();
        Duration queueWait = executor.map(e -> e.queueTimeout(lease)).orElse(lease);
        RunStepTransactions.ClaimOutcome outcome = transactions.claim(run.getId(), step.getId(), step.getAttempt(),
                now, now.plus(queueWait), resolvedInput, inClaim);
        switch (outcome) {
            case LOST -> LOG.info("Step {} claimed by another execution — stopping here", order);
            case FAILED -> LOG.warn("Step {} failed at dispatch — run halted, later steps stay pending", order);
            case DISPATCHED -> {
                StepExecutor claimed = executor.orElseThrow();
                if (claimed.mode() == StepExecutor.Mode.REMOTE) {
                    dispatchedJobs.increment();
                    LOG.info("Job dispatched: runId={} jobId={} stepOrder={} capability={} attempt={}", run.getId(),
                            step.getId(), order, step.getType(), step.getAttempt());
                } else {
                    submitInline(run.getId(), step, claimed, resolvedInput, definition);
                }
            }
            default -> throw new IllegalStateException("Unexpected claim outcome " + outcome);
        }
    }

    private void submitInline(UUID runId, RunStep step, StepExecutor executor, String resolvedInput,
            WorkflowStep definition) {
        StepContext context = new StepContext(runId, step.getId(), step.getStepOrder(), step.getType(),
                step.getAttempt(), resolvedInput, step.getInput(), definition == null ? null : definition.getConfig());
        try {
            taskExecutor.execute(() -> inRun(runId, () -> {
                try (StepLogContext ignored = StepLogContext.step(runId, context.stepId(), false, context.attempt(),
                        null)) {
                    runInline(executor, context);
                }
            }));
        } catch (TaskRejectedException e) {
            LOG.warn("Step {} could not be scheduled ({}) — left for the reaper to close at its queue deadline",
                    step.getStepOrder(), e.getMessage());
        }
    }

    private void runInline(StepExecutor executor, StepContext context) {
        Instant pickedUpAt = clock.instant();
        Instant runDeadline = pickedUpAt.plus(executor.maxRuntime());
        if (!transactions.recordPickup(context.stepId(), context.attempt(), pickedUpAt, pickedUpAt.plus(lease),
                runDeadline, INLINE_DELIVERY)) {
            LOG.info("Step {} closed before it was picked up — not running it", context.stepOrder());
            return;
        }
        LOG.info("Step {} running (type={})", context.stepOrder(), context.type());
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> heartbeat(context, runDeadline),
                pickedUpAt.plus(HEARTBEAT), HEARTBEAT);
        String output;
        try {
            output = executor.execute(context);
        } catch (StepFailedException e) {
            failInline(context, e.code(), e.getMessage(), null);
            return;
        } catch (RuntimeException e) {
            failInline(context, null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
            return;
        } finally {
            heartbeat.cancel(false);
        }
        try {
            if (transactions.complete(context.runId(), context.stepId(), context.attempt(), output, null,
                    clock.instant())) {
                LOG.info("Step {} completed", context.stepOrder());
            } else {
                discardLate(context.stepId(), context.attempt(), "completed");
            }
            advance(context.runId());
        } catch (RuntimeException e) {
            // Nothing else is waiting on this thread; the reaper's sweep re-advances the run.
            LOG.error("Could not record step {} or advance run {} past it", context.stepOrder(), context.runId(), e);
        }
    }

    private void failInline(StepContext context, String code, String message, Throwable cause) {
        if (transactions.fail(context.runId(), context.stepId(), context.attempt(), code, message, clock.instant())) {
            LOG.warn("Step {} failed: {} — run halted, later steps stay pending", context.stepOrder(), message, cause);
        } else {
            discardLate(context.stepId(), context.attempt(), "error");
        }
    }

    private void heartbeat(StepContext context, Instant runDeadline) {
        try {
            Instant now = clock.instant();
            transactions.extendLease(context.stepId(), context.attempt(), now, now.plus(lease), null,
                    INLINE_DELIVERY, runDeadline);
        } catch (RuntimeException e) {
            // A thrown exception would silently cancel every later heartbeat of this step.
            LOG.warn("Lease heartbeat for step {} failed; retrying at the next beat", context.stepId(), e);
        }
    }

    private void applyResult(MediaResult result) {
        short attempt = result.attempt().shortValue();
        Instant now = clock.instant();
        // Read before the transition: pickup time and type do not change when the step closes.
        Optional<RunStep> step = runStepRepository.findById(result.jobId());
        String capability = step.map(RunStep::getType).orElse("unknown");
        String durationMs = step.map(RunStep::getPickedUpAt)
                .map(pickedUpAt -> String.valueOf(Duration.between(pickedUpAt, result.finishedAt()).toMillis()))
                .orElse("n/a");
        if (result.status() == MediaResult.Status.COMPLETED) {
            String validationError = validateProviderData(capability, step.map(RunStep::getInput).orElse(null),
                    result.data());
            if (validationError != null) {
                if (transactions.fail(result.runId(), result.jobId(), attempt, "contract_violation",
                        validationError, now)) {
                    LOG.warn("Provider result rejected at trust boundary: runId={} jobId={} capability={} "
                            + "attempt={} reason={}", result.runId(), result.jobId(), capability, attempt,
                            validationError);
                } else {
                    discardLate(result.jobId(), attempt, result.status().value());
                }
                advance(result.runId());
                return;
            }
            if (transactions.complete(result.runId(), result.jobId(), attempt, json(result.data()),
                    json(result.outputs()), now,
                    () -> registerOutputs(result, step.orElseThrow(), attempt))) {
                LOG.info("Job result applied: runId={} jobId={} capability={} attempt={} deliveryCount={} "
                        + "outcome=completed durationMs={} worker={}", result.runId(), result.jobId(), capability,
                        attempt, result.deliveryCount(), durationMs, result.workerId());
                advance(result.runId());
                return;
            }
        } else if (transactions.fail(result.runId(), result.jobId(), attempt, result.error().code().value(),
                result.error().message(), now)) {
            LOG.warn("Job result applied: runId={} jobId={} capability={} attempt={} deliveryCount={} outcome=error "
                    + "code={} durationMs={} worker={}", result.runId(), result.jobId(), capability, attempt,
                    result.deliveryCount(), result.error().code().value(), durationMs, result.workerId());
            return;
        }
        discardLate(result.jobId(), attempt, result.status().value());
        // A redelivered result may be the only event left for a run whose first delivery completed
        // the step but died before advancing; advancing is idempotent.
        advance(result.runId());
    }

    /** Returns a validation-error message when a provider step's {@code data} payload does not match
     * its JSON Schema, otherwise {@code null}. A non-provider step (probe, extract_audio,
     * render_video, …) is left to media's {@code MediaContractSchemaTest} — the field set there is
     * heterogeneous and its own layer owns those contracts. Speech must also name the revision its
     * job input ({@code jobInput}, the step's stored input) asked for. */
    private String validateProviderData(String capability, String jobInput, Map<String, Object> data) {
        if (data == null) {
            return "provider result has no data";
        }
        try {
            Object parsed = switch (capability) {
                case "generate_script" -> objectMapper.convertValue(data, ProviderScriptData.class);
                case "generate_storyboard" -> objectMapper.convertValue(data, ProviderStoryboardData.class);
                case "generate_speech" -> objectMapper.convertValue(data, ProviderSpeechData.class);
                case "generate_video" -> objectMapper.convertValue(data, ProviderVideoData.class);
                default -> null;
            };
            if (parsed == null) {
                return null;
            }
            Set<ConstraintViolation<Object>> violations = validator.validate(parsed);
            if (!violations.isEmpty()) {
                String reason = violations.stream()
                        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .sorted()
                        .collect(Collectors.joining(", "));
                return "Provider " + capability + " data is not valid for its schema: " + reason;
            }
            if (parsed instanceof ProviderSpeechData speech) {
                String reason = provenanceMismatch(speech.sourceRevisionId(), jobInput);
                if (reason != null) {
                    return "Provider " + capability + " data is not valid for its schema: " + reason;
                }
            }
            return null;
        } catch (IllegalArgumentException e) {
            String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return "Provider " + capability + " data is not valid for its schema: " + reason;
        }
    }

    private String provenanceMismatch(UUID got, String jobInput) {
        UUID expected = sourceRevisionId(jobInput);
        if (expected == null) {
            return "sourceRevisionId " + got + " cannot be matched: the job input has no sourceRevisionId";
        }
        return expected.equals(got) ? null
                : "sourceRevisionId " + got + " does not match the job's " + expected;
    }

    private UUID sourceRevisionId(String jobInput) {
        if (jobInput == null || jobInput.isBlank()) {
            return null;
        }
        try {
            JsonNode revision = objectMapper.readTree(jobInput).path("sourceRevisionId");
            return revision.isTextual() ? UUID.fromString(revision.textValue()) : null;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    private void registerOutputs(MediaResult result, RunStep step, short attempt) {
        if (mediaJobs == null || result.outputs() == null || result.outputs().isEmpty()) {
            return;
        }
        WorkflowRun run = workflowRunRepository.findById(result.runId())
                .orElseThrow(() -> new IllegalStateException("Workflow run disappeared: " + result.runId()));
        mediaJobs.registerOutputs(run.getOwnerId(), result.runId(), result.jobId(), step.getStepOrder(), attempt,
                result.outputs());
    }

    private void applyProgress(MediaProgress progress) {
        RunStep step = runStepRepository.findById(progress.jobId()).orElse(null);
        if (step == null || !step.getRunId().equals(progress.runId())) {
            LOG.warn("Progress for unknown job {} of run {} ignored", progress.jobId(), progress.runId());
            return;
        }
        Optional<StepExecutor> executor = registry.find(step.getType());
        if (executor.isEmpty()) {
            LOG.warn("Progress for step {} of unknown type {} ignored", step.getId(), step.getType());
            return;
        }
        Duration maxRuntime = executor.get().maxRuntime();
        short attempt = progress.attempt().shortValue();
        short deliveryCount = progress.deliveryCount().shortValue();
        boolean applied;
        if (progress.kind() == MediaProgress.Kind.STARTED) {
            Instant at = progress.at();
            applied = transactions.recordPickup(step.getId(), attempt, at, at.plus(lease), at.plus(maxRuntime),
                    deliveryCount);
        } else {
            Instant now = clock.instant();
            Short percent = progress.percent() == null ? null : progress.percent().shortValue();
            applied = transactions.extendLease(step.getId(), attempt, now, now.plus(lease), percent, deliveryCount,
                    now.plus(maxRuntime));
        }
        if (!applied) {
            LOG.debug("{} for closed step {} (attempt {}) ignored", progress.kind().value(), step.getId(), attempt);
        } else if (progress.kind() == MediaProgress.Kind.STARTED) {
            LOG.info("Job picked up: runId={} jobId={} capability={} attempt={} deliveryCount={}", progress.runId(),
                    progress.jobId(), step.getType(), attempt, deliveryCount);
        } else {
            LOG.info("Job progress: runId={} jobId={} capability={} attempt={} deliveryCount={} percent={}",
                    progress.runId(), progress.jobId(), step.getType(), attempt, deliveryCount, progress.percent());
        }
    }

    private void discardLate(UUID stepId, short attempt, String status) {
        lateResults.increment();
        String current = runStepRepository.findById(stepId)
                .map(step -> "the step is " + step.getStatus().name().toLowerCase(Locale.ROOT)
                        + (step.getErrorCode() == null ? "" : " (" + step.getErrorCode() + ")"))
                .orElse("the step does not exist");
        LOG.warn("Late or duplicate {} result for step {} (attempt {}) discarded — {}", status, stepId, attempt,
                current);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A validated result could not be serialized", e);
        }
    }

    /**
     * {@code runId} on the run's log lines, and as baggage on the job messages and spans it starts.
     * The baggage scope writes the run id into the enclosing span's shared context and takes it out
     * again on close, while the outbox sends a job from its own task, which may start after that.
     * The run's own span, started inside the scope, keeps the run id for whatever continues it.
     */
    private void inRun(UUID runId, Runnable action) {
        try (StepLogContext ignored = StepLogContext.run(runId);
                BaggageInScope baggage = tracer.createBaggageInScope(BAGGAGE_RUN_ID, runId.toString())) {
            Observation.createNotStarted(RUN_OBSERVATION, observations)
                    .highCardinalityKeyValue(BAGGAGE_RUN_ID, runId.toString())
                    .observe(action);
        }
    }
}
