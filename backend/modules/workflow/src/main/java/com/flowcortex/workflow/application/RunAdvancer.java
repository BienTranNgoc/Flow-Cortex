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
import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunOptions;
import com.flowcortex.workflow.application.provider.DubProperties;
import com.flowcortex.workflow.application.provider.ProviderStepPool;
import com.flowcortex.workflow.application.vendor.VendorJobLedger;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
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
 * result — never in a loop, except past an iterate step that completes in the pass, which is read
 * again in a transaction of its own. A running iterate step is progressed on every advance: its
 * items are read, and pending ones are claimed under its concurrency cap, until it completes or fails.
 * {@link #advance} claims the lowest pending step and hands it on: an
 * inline step to Boot's {@code applicationTaskExecutor} (a provider-backed one to its own bounded
 * pool, failing {@code provider_busy} when that is full), whose task records pickup, heartbeats
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
    private static final String PROVIDER_BUSY = "provider_busy";
    private static final int CONSUME_TRIES = 2;
    /** Inline steps have no broker, so no redeliveries to count. */
    private static final short INLINE_DELIVERY = 0;
    /** What the constructors without the iterate settings use: one provider-pool item at a time of a pool of four. */
    private static final int DEFAULT_ITERATE_PROVIDER_CONCURRENCY = 1;
    private static final int DEFAULT_PROVIDER_POOL_SIZE = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StoredWithOutputsResolver storedWithOutputsResolver = new StoredWithOutputsResolver();
    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final RunStepTransactions transactions;
    private final InputResolver inputResolver;
    private final StepExecutorRegistry registry;
    private final TaskExecutor taskExecutor;
    private final ProviderStepPool providerPool;
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
    private final VendorJobLedger vendorLedger;
    private final boolean parallelClipsEnabled;
    private final int iterateProviderConcurrency;

    @Autowired
    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, InputResolver inputResolver, StepExecutorRegistry registry,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor, ProviderStepPool providerPool,
            TaskScheduler scheduler,
            ObjectProvider<Tracer> tracer, ObjectProvider<ObservationRegistry> observations, MeterRegistry meters,
            @Value("${flowcortex.workflow.lease-seconds}") long leaseSeconds,
            ReviewCheckpointTransactions checkpointTransactions, MediaJobs mediaJobs, Validator validator,
            VendorJobLedger vendorLedger,
            @Value("${flowcortex.product-video.parallel-clips-enabled:false}") boolean parallelClipsEnabled,
            @Value("${flowcortex.workflow.iterate.provider-concurrency}") int iterateProviderConcurrency,
            DubProperties dub) {
        this(workflowRunRepository, runStepRepository, transactions, inputResolver, registry,
                taskExecutor, providerPool, scheduler, tracer.getIfAvailable(() -> Tracer.NOOP),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP), meters, Duration.ofSeconds(leaseSeconds),
                Clock.systemUTC(), checkpointTransactions, mediaJobs, validator, vendorLedger, parallelClipsEnabled,
                iterateProviderConcurrency, dub.pool().size());
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, InputResolver inputResolver, StepExecutorRegistry registry,
            TaskExecutor taskExecutor, ProviderStepPool providerPool, TaskScheduler scheduler, Tracer tracer,
            ObservationRegistry observations,
            MeterRegistry meters, Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions,
            Validator validator) {
        this(workflowRunRepository, runStepRepository, transactions, inputResolver, registry,
                taskExecutor, providerPool, scheduler, tracer, observations, meters, lease, clock, checkpointTransactions, null,
                validator, null, false);
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, InputResolver inputResolver, StepExecutorRegistry registry,
            TaskExecutor taskExecutor, ProviderStepPool providerPool, TaskScheduler scheduler, Tracer tracer,
            ObservationRegistry observations,
            MeterRegistry meters, Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions,
            Validator validator, boolean parallelClipsEnabled) {
        this(workflowRunRepository, runStepRepository, transactions, inputResolver, registry,
                taskExecutor, providerPool, scheduler, tracer, observations, meters, lease, clock, checkpointTransactions, null,
                validator, null, parallelClipsEnabled);
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, InputResolver inputResolver, StepExecutorRegistry registry,
            TaskExecutor taskExecutor, ProviderStepPool providerPool, TaskScheduler scheduler, Tracer tracer,
            ObservationRegistry observations,
            MeterRegistry meters, Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions,
            Validator validator, VendorJobLedger vendorLedger) {
        this(workflowRunRepository, runStepRepository, transactions, inputResolver, registry,
                taskExecutor, providerPool, scheduler, tracer, observations, meters, lease, clock, checkpointTransactions, null,
                validator, vendorLedger, false);
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, InputResolver inputResolver, StepExecutorRegistry registry,
            TaskExecutor taskExecutor, ProviderStepPool providerPool, TaskScheduler scheduler, Tracer tracer,
            ObservationRegistry observations,
            MeterRegistry meters, Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions,
            MediaJobs mediaJobs, Validator validator, VendorJobLedger vendorLedger) {
        this(workflowRunRepository, runStepRepository, transactions, inputResolver, registry, taskExecutor,
                providerPool, scheduler, tracer, observations, meters, lease, clock, checkpointTransactions,
                mediaJobs, validator, vendorLedger, false, DEFAULT_ITERATE_PROVIDER_CONCURRENCY,
                DEFAULT_PROVIDER_POOL_SIZE);
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, InputResolver inputResolver, StepExecutorRegistry registry,
            TaskExecutor taskExecutor, ProviderStepPool providerPool, TaskScheduler scheduler, Tracer tracer,
            ObservationRegistry observations,
            MeterRegistry meters, Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions,
            MediaJobs mediaJobs, Validator validator, VendorJobLedger vendorLedger, boolean parallelClipsEnabled) {
        this(workflowRunRepository, runStepRepository, transactions, inputResolver, registry, taskExecutor,
                providerPool, scheduler, tracer, observations, meters, lease, clock, checkpointTransactions,
                mediaJobs, validator, vendorLedger, parallelClipsEnabled, DEFAULT_ITERATE_PROVIDER_CONCURRENCY,
                DEFAULT_PROVIDER_POOL_SIZE);
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, InputResolver inputResolver, StepExecutorRegistry registry,
            TaskExecutor taskExecutor, ProviderStepPool providerPool, TaskScheduler scheduler, Tracer tracer,
            ObservationRegistry observations,
            MeterRegistry meters, Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions,
            MediaJobs mediaJobs, Validator validator, VendorJobLedger vendorLedger, int iterateProviderConcurrency,
            int providerPoolSize) {
        this(workflowRunRepository, runStepRepository, transactions, inputResolver, registry, taskExecutor,
                providerPool, scheduler, tracer, observations, meters, lease, clock, checkpointTransactions,
                mediaJobs, validator, vendorLedger, false, iterateProviderConcurrency, providerPoolSize);
    }

    RunAdvancer(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, InputResolver inputResolver, StepExecutorRegistry registry,
            TaskExecutor taskExecutor, ProviderStepPool providerPool, TaskScheduler scheduler, Tracer tracer,
            ObservationRegistry observations,
            MeterRegistry meters, Duration lease, Clock clock, ReviewCheckpointTransactions checkpointTransactions,
            MediaJobs mediaJobs, Validator validator, VendorJobLedger vendorLedger, boolean parallelClipsEnabled,
            int iterateProviderConcurrency, int providerPoolSize) {
        if (iterateProviderConcurrency < 1 || iterateProviderConcurrency > providerPoolSize) {
            throw new IllegalArgumentException("flowcortex.workflow.iterate.provider-concurrency must be 1 to "
                    + providerPoolSize + " (the provider pool size), not " + iterateProviderConcurrency);
        }
        this.iterateProviderConcurrency = iterateProviderConcurrency;
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.transactions = transactions;
        this.inputResolver = inputResolver;
        this.registry = registry;
        this.taskExecutor = taskExecutor;
        this.providerPool = providerPool;
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
        this.vendorLedger = vendorLedger;
        this.parallelClipsEnabled = parallelClipsEnabled;
    }

    @ApplicationModuleListener
    void on(WorkflowRunStartedEvent event) {
        inRun(event.runId(), () -> advance(event.runId(), true));
    }

    @ApplicationModuleListener
    void onResumed(WorkflowRunResumedEvent event) {
        inRun(event.runId(), () -> advance(event.runId()));
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

    /**
     * One pass per transaction: an iterate step that completes inside a pass asks for another, which reads
     * the run's steps again in a transaction of its own, so a thread never holds a nested advance.
     */
    private void advance(UUID runId, boolean starting) {
        boolean first = starting;
        boolean again;
        do {
            boolean logStart = first;
            first = false;
            again = transactions.callInNewTransaction(
                    () -> inRunCalling(runId, () -> advanceInRun(runId, logStart)));
        } while (again);
    }

    /** @return whether an iterate step just completed, so the run's next step is to be read again */
    private boolean advanceInRun(UUID runId, boolean starting) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null) {
            LOG.warn("Workflow run {} not found — nothing to advance (should not happen)", runId);
            return false;
        }
        List<RunStep> runSteps = runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        if (starting && run.getStatus() == RunStatus.RUNNING) {
            LOG.info("Workflow run started: runId={} steps={}", runId, runSteps.size());
        }
        Map<Integer, String> outputsByOrder = new LinkedHashMap<>();
        Map<Integer, String> assetOutputsByOrder = new LinkedHashMap<>();
        for (RunStep runStep : runSteps) {
            if (runStep.getStatus() == RunStepStatus.COMPLETED) {
                outputsByOrder.put((int) runStep.getStepOrder(), runStep.getOutput());
                assetOutputsByOrder.put((int) runStep.getStepOrder(), runStep.getOutputs());
            }
        }
        RunStep firstOpen = runSteps.stream()
                .filter(runStep -> runStep.getStatus() != RunStepStatus.COMPLETED)
                .findFirst()
                .orElse(null);
        if (run.getStatus() == RunStatus.RUNNING && firstOpen != null
                && firstOpen.getStatus() == RunStepStatus.RUNNING && IterateStepConfig.TYPE.equals(firstOpen.getType())) {
            IterateStepConfig config = readIterateConfig(run, firstOpen);
            return config != null && progressIterate(run, firstOpen, config, firstOpen.getInput());
        }
        ReadyStepSelector.Decision decision = ReadyStepSelector.select(
                parallelClipsEnabled, run.getWorkflowId(), run.getStatus(), runSteps);
        switch (decision) {
            case ReadyStepSelector.Decision.Halt ignored -> {
            }
            case ReadyStepSelector.Decision.Wait ignored ->
                LOG.info("Run {} waiting for in-flight step(s)", runId);
            case ReadyStepSelector.Decision.CompleteRun ignored ->
                transactions.completeRunIfDone(runId, clock.instant());
            case ReadyStepSelector.Decision.PauseForApproval pause -> reviewOrFail(run, pause.step(), outputsByOrder);
            case ReadyStepSelector.Decision.Dispatch dispatch -> {
                for (RunStep step : dispatch.steps()) {
                    Dispatched result = dispatch(run, step, outputsByOrder, assetOutputsByOrder);
                    if (result.iterateCompleted()) {
                        return true;
                    }
                    if (result.outcome() == RunStepTransactions.ClaimOutcome.FAILED) {
                        break;
                    }
                }
            }
        }
        return false;
    }

    /** A dispatch's claim outcome, and whether it was an iterate step that completed within the call. */
    private record Dispatched(RunStepTransactions.ClaimOutcome outcome, boolean iterateCompleted) {
    }

    /**
     * A human step reads its content like any other step. A reference that cannot be read fails the
     * step through its claim, as {@link #dispatch} does, so the run ends in error with no review task.
     */
    private void reviewOrFail(WorkflowRun run, RunStep step, Map<Integer, String> outputsByOrder) {
        String content;
        try {
            content = inputResolver.resolve(step.getStepOrder(), step.getInputRef(), run.getInput(), outputsByOrder);
        } catch (StepFailedException | IllegalStateException e) {
            try (StepLogContext ignored = StepLogContext.step(run.getId(), step.getId(), false, step.getAttempt(),
                    null)) {
                Instant now = clock.instant();
                RunStepTransactions.ClaimOutcome outcome = transactions.claim(run.getId(), step.getId(),
                        step.getAttempt(), now, now.plus(lease), null, resolutionFailure(e));
                switch (outcome) {
                    case LOST -> LOG.info("Step {} claimed by another execution — stopping here", step.getStepOrder());
                    case FAILED -> LOG.warn("Step {} failed reading its content — run halted, later steps stay pending",
                            step.getStepOrder());
                    default -> throw new IllegalStateException("Unexpected claim outcome " + outcome);
                }
            }
            return;
        }
        checkpointTransactions.pause(run, step, content, clock.instant());
    }

    /** The claim body that fails the step with what its input resolution threw. */
    private static Runnable resolutionFailure(RuntimeException e) {
        if (e instanceof StepFailedException failed) {
            return () -> {
                throw failed;
            };
        }
        return () -> {
            throw new StepFailedException(null, e.getMessage());
        };
    }

    private Dispatched dispatch(WorkflowRun run, RunStep step, Map<Integer, String> outputsByOrder,
            Map<Integer, String> assetOutputsByOrder) {
        Optional<StepExecutor> executor = registry.find(step.getType());
        boolean remote = executor.filter(e -> e.mode() == StepExecutor.Mode.REMOTE).isPresent();
        try (StepLogContext ignored = StepLogContext.step(run.getId(), step.getId(), remote, step.getAttempt(), null)) {
            return dispatch(run, step, executor, outputsByOrder, assetOutputsByOrder);
        }
    }

    /**
     * Reads config and input reference only from the run step's own snapshot, never from the
     * current workflow definition: a run in flight must not change shape when the workflow does.
     *
     */
    private Dispatched dispatch(WorkflowRun run, RunStep step, Optional<StepExecutor> executor,
            Map<Integer, String> outputsByOrder, Map<Integer, String> assetOutputsByOrder) {
        int order = step.getStepOrder();
        boolean remote = executor.filter(e -> e.mode() == StepExecutor.Mode.REMOTE).isPresent();
        String resolvedInput = null;
        StepContext context = null;
        Runnable inClaim;
        try {
            String stepConfig = step.getConfig();
            StepExecutor.InputPolicy inputPolicy = registry.inputPolicy(step.getType(), stepConfig);
            resolvedInput = switch (inputPolicy) {
                case STORED -> step.getInput();
                case STORED_WITH_OUTPUTS -> storedWithOutputsResolver.resolve(step.getInput(),
                        stepConfig, assetOutputsByOrder, run.getId(), order);
                case RESOLVED -> resolveReference(order, step, run, outputsByOrder);
            };
            StepContext built = new StepContext(run.getId(), step.getId(), order, step.getType(), step.getAttempt(),
                    resolvedInput, stepConfig, step.getInput(), options(run), new ArrayList<>());
            context = built;
            if (executor.isEmpty()) {
                inClaim = () -> {
                    throw new StepFailedException("unsupported_step_type", "Unsupported step type: " + step.getType());
                };
            } else if (remote || executor.get().mode() == StepExecutor.Mode.NESTED
                    || executor.get().mode() == StepExecutor.Mode.FANOUT) {
                inClaim = () -> executor.get().execute(built);
            } else {
                inClaim = () -> { };
            }
        } catch (StepFailedException | IllegalStateException e) {
            inClaim = resolutionFailure(e);
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
                } else if (claimed.mode() == StepExecutor.Mode.NESTED) {
                    recordNestedDeadline(context, claimed);
                } else if (claimed.mode() == StepExecutor.Mode.FANOUT) {
                    return new Dispatched(outcome, startFanout(run, step, resolvedInput));
                } else {
                    submitInline(run.getId(), step, claimed, context);
                }
            }
            default -> throw new IllegalStateException("Unexpected claim outcome " + outcome);
        }
        return new Dispatched(outcome, false);
    }

    /**
     * An iterate step whose items were created in its claim: its deadline is the rounds its items need at
     * the effective concurrency, then the first items start. An empty list gets the engine lease, so the
     * reaper cannot close it between this write and its completion.
     */
    private boolean startFanout(WorkflowRun run, RunStep step, String listInput) {
        IterateStepConfig config = readIterateConfig(run, step);
        if (config == null) {
            return false;
        }
        int items = runStepRepository.findChildStates(step.getId(), step.getAttempt()).size();
        Optional<StepExecutor> child = registry.find(config.childType());
        Duration deadline = IterateProgress.deadline(items, effectiveConcurrency(config),
                child.map(executor -> executor.queueTimeout(lease)).orElse(lease),
                child.map(StepExecutor::maxRuntime).orElse(Duration.ZERO), lease);
        Instant pickedUpAt = clock.instant();
        Instant runDeadline = pickedUpAt.plus(deadline);
        if (!transactions.recordPickup(run.getId(), step.getId(), step.getAttempt(), pickedUpAt,
                runDeadline.plus(lease), runDeadline, INLINE_DELIVERY)) {
            LOG.info("Step {} closed before its deadline was recorded", step.getStepOrder());
            return false;
        }
        return progressIterate(run, step, config, listInput);
    }

    /** The config the claim already parsed; a step that cannot read it again fails rather than waits for its deadline. */
    private IterateStepConfig readIterateConfig(WorkflowRun run, RunStep step) {
        try {
            return IterateStepConfig.parse(step.getConfig());
        } catch (IllegalArgumentException e) {
            LOG.error("Iterate step {} of run {} holds a config it cannot read", step.getId(), run.getId(), e);
            transactions.fail(run.getId(), step.getId(), step.getAttempt(), "iterate_config_invalid", e.getMessage(),
                    clock.instant());
            return null;
        }
    }

    /**
     * How many items run at once: {@code concurrency}, but for items that wait on a provider no more than
     * the provider-pool setting, so one step cannot fill the pool every member's dub steps share.
     */
    int effectiveConcurrency(IterateStepConfig config) {
        boolean provider = registry.find(config.childType()).map(e -> e.pool() == StepExecutor.Pool.PROVIDER)
                .orElse(false);
        return provider ? Math.min(config.concurrency(), iterateProviderConcurrency) : config.concurrency();
    }

    /**
     * Decides what the running iterate step does next from its items as the database holds them, and does
     * it. It decides again only after a pass in which an item failed in its claim or was refused by the
     * provider pool: no thread runs for such an item, so nothing else would advance the run, and each one
     * turns a pending item terminal, which bounds the passes by the item count. A pass whose claims were all
     * lost, or that only started items, stops here: a running item's end advances the run.
     *
     * @param listInput the resolved list of the step, as the engine holds it now rather than as the entity read
     *                  earlier in the advance does
     * @return whether the step completed, so the run's next step is to be read again
     */
    private boolean progressIterate(WorkflowRun run, RunStep step, IterateStepConfig config, String listInput) {
        int concurrency = effectiveConcurrency(config);
        int dropped = IterateItems.classify(listInput, config.maxItems()) instanceof IterateItems.Items items
                ? items.dropped() : 0;
        int endedInPass;
        do {
            List<IterateProgress.ChildState> children = runStepRepository
                    .findChildStates(step.getId(), step.getAttempt()).stream()
                    .map(IterateProgress.ChildState::of)
                    .toList();
            switch (IterateProgress.decide(config, children, dropped, concurrency)) {
                case IterateProgress.Wait wait -> {
                    return false;
                }
                case IterateProgress.Complete done -> {
                    return completeIterate(run, step, done);
                }
                case IterateProgress.Fail fail -> {
                    failIterate(run, step, fail);
                    return false;
                }
                case IterateProgress.Dispatch dispatch ->
                        endedInPass = dispatchItems(run, step, config, listInput, dispatch, concurrency);
            }
        } while (endedInPass > 0);
        return false;
    }

    private boolean completeIterate(WorkflowRun run, RunStep step, IterateProgress.Complete done) {
        if (transactions.complete(run.getId(), step.getId(), step.getAttempt(), done.output(), done.outputs(),
                clock.instant())) {
            LOG.info("Step finished: runId={} runStepId={} stepOrder={} type={} outcome=completed", run.getId(),
                    step.getId(), step.getStepOrder(), step.getType());
            return true;
        }
        discardLate(step.getId(), step.getAttempt(), "completed");
        return false;
    }

    private void failIterate(WorkflowRun run, RunStep step, IterateProgress.Fail fail) {
        if (transactions.fail(run.getId(), step.getId(), step.getAttempt(), fail.code(), fail.message(),
                clock.instant())) {
            LOG.warn("Step finished: runId={} runStepId={} stepOrder={} type={} outcome=error code={} — run halted, "
                    + "later steps stay pending: {}", run.getId(), step.getId(), step.getStepOrder(), step.getType(),
                    fail.code(), fail.message());
        } else {
            discardLate(step.getId(), step.getAttempt(), "error");
        }
    }

    /** @return how many of the items failed in their claim or were refused by the provider pool, so no thread of theirs advances the run */
    private int dispatchItems(WorkflowRun run, RunStep step, IterateStepConfig config, String listInput,
            IterateProgress.Dispatch dispatch, int concurrency) {
        Map<UUID, RunStep> items = runStepRepository.findChildren(step.getId(), step.getAttempt()).stream()
                .collect(Collectors.toMap(RunStep::getId, item -> item));
        int failed = 0;
        for (UUID id : dispatch.childIds()) {
            RunStep item = items.get(id);
            if (item != null
                    && dispatchItem(run, step, config, listInput, item, concurrency, false)
                            == RunStepTransactions.ClaimOutcome.FAILED) {
                failed++;
            }
        }
        return failed;
    }

    /**
     * Claims one item under the concurrency cap and hands it to its thread pool. Its config is rendered for
     * its item first; a render that fails fails the item inside the claim, so its charge is refunded. An item
     * the provider pool refuses after the claim fails {@code provider_busy} here and is reported as
     * {@code FAILED}, like a failure in the claim: no thread runs for it.
     * {@code listInput} is the parent's resolved list: it alone tells a string element that reads like JSON
     * from the JSON value, so the caller passes the one it holds instead of this method reading the entity.
     */
    private RunStepTransactions.ClaimOutcome dispatchItem(WorkflowRun run, RunStep parent, IterateStepConfig config,
            String listInput, RunStep item, int limit, boolean regenerated) {
        Optional<StepExecutor> executor = registry.find(item.getType());
        try (StepLogContext ignored = StepLogContext.step(run.getId(), item.getId(), false, item.getAttempt(),
                null)) {
            StepContext context = null;
            Runnable inClaim = () -> { };
            if (executor.isEmpty()) {
                inClaim = () -> {
                    throw new StepFailedException(null, "Unsupported step type: " + item.getType());
                };
            } else {
                try {
                    boolean textItem = IterateItems.isTextItem(listInput, item.getItemIndex(), item.getInput());
                    context = new StepContext(run.getId(), item.getId(), item.getStepOrder(), item.getType(),
                            item.getAttempt(), item.getInput(),
                            IterateItems.render(config.childConfig(), item.getInput(), textItem, item.getItemIndex()),
                            item.getInput(), options(run), new ArrayList<>(), (int) item.getItemIndex());
                } catch (StepFailedException e) {
                    inClaim = () -> {
                        throw e;
                    };
                }
            }
            Instant now = clock.instant();
            Duration queueWait = executor.map(e -> e.queueTimeout(lease)).orElse(lease);
            RunStepTransactions.ClaimOutcome outcome = transactions.claimChild(run.getId(), item.getId(),
                    item.getAttempt(), now, now.plus(queueWait), item.getInput(), parent.getId(),
                    parent.getAttempt(), limit, regenerated, inClaim);
            switch (outcome) {
                case LOST -> LOG.info("Item {} of step {} not started: claimed elsewhere or the cap is reached",
                        item.getItemIndex(), parent.getStepOrder());
                case FAILED -> LOG.warn("Item {} of step {} failed at dispatch", item.getItemIndex(),
                        parent.getStepOrder());
                case DISPATCHED -> {
                    if (!submitInline(run.getId(), item, executor.orElseThrow(), context)) {
                        return RunStepTransactions.ClaimOutcome.FAILED;
                    }
                }
                default -> throw new IllegalStateException("Unexpected claim outcome " + outcome);
            }
            return outcome;
        }
    }

    /** Starts the item a regenerate re-armed; the run and its iterate step stay completed. */
    @ApplicationModuleListener
    void onChildRegenerated(IterateChildRegenerated event) {
        inRun(event.runId(), () -> {
            WorkflowRun run = workflowRunRepository.findById(event.runId()).orElse(null);
            RunStep item = runStepRepository.findById(event.childId()).orElse(null);
            RunStep parent = item == null || item.getParentStepId() == null ? null
                    : runStepRepository.findById(item.getParentStepId()).orElse(null);
            if (run == null || parent == null || !item.getRunId().equals(event.runId())) {
                LOG.warn("Regenerated item {} of run {} has no iterate step to run under — nothing to dispatch",
                        event.childId(), event.runId());
                return;
            }
            IterateStepConfig config;
            try {
                config = IterateStepConfig.parse(parent.getConfig());
            } catch (IllegalArgumentException e) {
                LOG.error("Iterate step {} of run {} holds a config it cannot read", parent.getId(), run.getId(), e);
                return;
            }
            dispatchItem(run, parent, config, parent.getInput(), item, effectiveConcurrency(config), true);
        });
    }

    /**
     * The lease outlives the run deadline, so a child that never reports back is reaped as {@code run_timeout}.
     * A {@code false} means the child already ended and closed the step, which needs nothing more.
     */
    private void recordNestedDeadline(StepContext context, StepExecutor executor) {
        Instant pickedUpAt = clock.instant();
        Instant runDeadline = pickedUpAt.plus(executor.maxRuntime());
        if (!transactions.recordPickup(context.runId(), context.stepId(), context.attempt(), pickedUpAt,
                runDeadline.plus(lease), runDeadline, INLINE_DELIVERY)) {
            LOG.info("Step {} closed before its deadline was recorded", context.stepOrder());
        }
    }

    /** The options the run was started with; a run started before runs kept options gets the defaults. */
    /**
     * Only a reference the resolver cannot read is a handoff fault; any other {@code IllegalStateException}
     * of the dispatch (run options that are not an object, say) keeps no code.
     */
    private String resolveReference(int order, RunStep step, WorkflowRun run, Map<Integer, String> outputsByOrder) {
        try {
            return inputResolver.resolve(order, step.getInputRef(), run.getInput(), outputsByOrder);
        } catch (IllegalStateException e) {
            throw new StepFailedException("input_unresolved", e.getMessage(), e);
        }
    }

    private RunOptions options(WorkflowRun run) {
        if (run.getOptions() == null) {
            return RunOptions.DEFAULTS;
        }
        try {
            return objectMapper.readValue(run.getOptions(), RunOptions.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Run " + run.getId() + " holds options that are not a JSON object", e);
        }
    }

    /** @return {@code false} when the provider pool refused the step and it failed {@code provider_busy} here */
    private boolean submitInline(UUID runId, RunStep step, StepExecutor executor, StepContext context) {
        Runnable task = () -> inRun(runId, () -> {
            try (StepLogContext ignored = StepLogContext.step(runId, context.stepId(), false, context.attempt(),
                    null)) {
                runInline(executor, context);
            }
        });
        boolean provider = executor.pool() == StepExecutor.Pool.PROVIDER;
        try {
            if (provider) {
                providerPool.execute(task);
            } else {
                taskExecutor.execute(task);
            }
        } catch (TaskRejectedException e) {
            if (provider) {
                failProviderBusy(context, e);
                return false;
            }
            LOG.warn("Step {} could not be scheduled ({}) — left for the reaper to close at its queue deadline",
                    step.getStepOrder(), e.getMessage());
        }
        return true;
    }

    /** A provider step that finds every provider thread busy and the queue full fails now rather than waiting. */
    private void failProviderBusy(StepContext context, TaskRejectedException cause) {
        failInline(context, PROVIDER_BUSY, "Too many videos are being processed right now. Try again in a few minutes.",
                cause, clock.instant(), false);
    }

    private void runInline(StepExecutor executor, StepContext context) {
        Instant pickedUpAt = clock.instant();
        Instant runDeadline = pickedUpAt.plus(executor.maxRuntime());
        if (!transactions.recordPickup(context.runId(), context.stepId(), context.attempt(), pickedUpAt,
                pickedUpAt.plus(lease), runDeadline, INLINE_DELIVERY)) {
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
            failInline(context, e.code(), e.getMessage(), null, pickedUpAt, true);
            return;
        } catch (RuntimeException e) {
            failInline(context, null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e,
                    pickedUpAt, true);
            return;
        } finally {
            heartbeat.cancel(false);
        }
        try {
            String stored = context.storedOutputs().isEmpty() ? null : json(context.storedOutputs());
            Instant now = clock.instant();
            if (transactions.complete(context.runId(), context.stepId(), context.attempt(), output, stored, now)) {
                LOG.info("Step finished: runId={} runStepId={} stepOrder={} type={} outcome=completed durationMs={}",
                        context.runId(), context.stepId(), context.stepOrder(), context.type(),
                        Duration.between(pickedUpAt, now).toMillis());
            } else {
                discardLate(context.stepId(), context.attempt(), "completed");
            }
            advance(context.runId());
        } catch (RuntimeException e) {
            // Nothing else is waiting on this thread; the reaper's sweep re-advances the run.
            LOG.error("Could not record step {} or advance run {} past it", context.stepOrder(), context.runId(), e);
        }
    }

    /**
     * A top-level step's failure halts its run. An item of an iterate step leaves the run to the iterate
     * step, so a failure that happened on this thread advances the run, unless the caller is itself the
     * iterate step's own pass ({@code advanceAfterItem} false), which decides again itself because
     * {@code dispatchItem} reports the item as failed.
     */
    private void failInline(StepContext context, String code, String message, Throwable cause, Instant pickedUpAt,
            boolean advanceAfterItem) {
        Instant now = clock.instant();
        boolean item = context.itemIndex() != null;
        if (transactions.fail(context.runId(), context.stepId(), context.attempt(), code, message, now)) {
            LOG.warn("Step finished: runId={} runStepId={} stepOrder={} type={} outcome=error code={} durationMs={} "
                    + (item ? "itemIndex=" + context.itemIndex() + " — item of an iterate step: {}"
                            : "— run halted, later steps stay pending: {}"), context.runId(), context.stepId(),
                    context.stepOrder(), context.type(), code, Duration.between(pickedUpAt, now).toMillis(), message,
                    cause);
            if (item && advanceAfterItem) {
                advanceAfterItemFailure(context);
            }
        } else {
            discardLate(context.stepId(), context.attempt(), "error");
        }
    }

    private void advanceAfterItemFailure(StepContext context) {
        try {
            advance(context.runId());
        } catch (RuntimeException e) {
            // Nothing else is waiting on this thread; the reaper's sweep re-advances the run.
            LOG.error("Could not advance run {} past failed item {} of step {}", context.runId(),
                    context.itemIndex(), context.stepOrder(), e);
        }
    }

    private void heartbeat(StepContext context, Instant runDeadline) {
        try {
            Instant now = clock.instant();
            transactions.extendLease(context.runId(), context.stepId(), context.attempt(), now, now.plus(lease), null,
                    INLINE_DELIVERY, runDeadline);
        } catch (RuntimeException e) {
            // A thrown exception would silently cancel every later heartbeat of this step.
            LOG.warn("Lease heartbeat for step {} failed; retrying at the next beat", context.stepId(), e);
        }
    }

    private void applyResult(MediaResult result) {
        short attempt = result.attempt().shortValue();
        Instant now = clock.instant();
        // Read before the transition: pickup time and type do not change when the step closes. Not
        // as an entity: the advance after the transition must see the step closed, not as cached here.
        Optional<RunStepRepository.Timing> step = runStepRepository.findTimingById(result.jobId());
        String capability = step.map(RunStepRepository.Timing::getType).orElse("unknown");
        String durationMs = step.map(RunStepRepository.Timing::getPickedUpAt)
                .map(pickedUpAt -> String.valueOf(Duration.between(pickedUpAt, result.finishedAt()).toMillis()))
                .orElse("n/a");
        if (result.status() == MediaResult.Status.COMPLETED) {
            Rejection rejection = rejectProviderData(capability,
                    step.map(RunStepRepository.Timing::getInput).orElse(null), result.data());
            if (rejection != null) {
                if (transactions.fail(result.runId(), result.jobId(), attempt, rejection.code(),
                        rejection.message(), now)) {
                    LOG.warn("Provider result rejected at trust boundary: runId={} jobId={} capability={} "
                            + "attempt={} code={} reason={}", result.runId(), result.jobId(), capability, attempt,
                            rejection.code(), rejection.message());
                } else {
                    discardLate(result.jobId(), attempt, result.status().value());
                }
                advance(result.runId());
                return;
            }
            if (transactions.complete(result.runId(), result.jobId(), attempt, json(result.data()),
                    json(result.outputs()), now,
                    () -> registerOutputs(result, step.orElseThrow().getStepOrder(), attempt))) {
                LOG.info("Job result applied: runId={} jobId={} capability={} attempt={} deliveryCount={} "
                        + "outcome=completed durationMs={} worker={}", result.runId(), result.jobId(), capability,
                        attempt, result.deliveryCount(), durationMs, result.workerId());
                consumeVendorJob(result, capability);
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

    /** Why a completed provider result fails its step: the step error code and its message. */
    private record Rejection(String code, String message) {
    }

    /**
     * Marks the vendor job a delivered clip came from as consumed. The step is already completed, so a
     * ledger failure is retried once and then logged: it must never fail a step the member paid for.
     */
    private void consumeVendorJob(MediaResult result, String capability) {
        if (vendorLedger == null || !"generate_video".equals(capability)) {
            return;
        }
        for (int attempt = 1; attempt <= CONSUME_TRIES; attempt++) {
            try {
                vendorLedger.consume(result.runId(), result.jobId(), capability, result.data());
                return;
            } catch (RuntimeException e) {
                if (attempt == CONSUME_TRIES) {
                    LOG.warn("vendor_job outcome=consume_failed run={} step={} vendor={} job={}", result.runId(),
                            result.jobId(), result.data().get("provider"), result.data().get("providerTaskId"), e);
                }
            }
        }
    }

    /** Returns the rejection of a completed provider step's {@code data} payload, or {@code null}
     * when the step may complete. A payload that does not match its JSON Schema is a
     * {@code contract_violation}; speech longer than the Product Video would be cut off by the
     * renderer, so it is {@code speech_too_long}. */
    private Rejection rejectProviderData(String capability, String jobInput, Map<String, Object> data) {
        String validationError = validateProviderData(capability, jobInput, data);
        if (validationError != null) {
            return new Rejection("contract_violation", validationError);
        }
        if ("generate_speech".equals(capability)) {
            int durationMs = objectMapper.convertValue(data, ProviderSpeechData.class).durationMs();
            if (durationMs > ProductVideoWorkflow.VIDEO_DURATION_SECONDS * 1000) {
                // Rounded up so a speech just over the limit never reads as exactly the video length.
                return new Rejection("speech_too_long", String.format(Locale.ROOT,
                        "The approved script reads for %.1f s, longer than the %d s video. "
                                + "Start a new run with a shorter script.",
                        Math.ceil(durationMs / 100.0) / 10, ProductVideoWorkflow.VIDEO_DURATION_SECONDS));
            }
        }
        return null;
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

    private void registerOutputs(MediaResult result, int stepOrder, short attempt) {
        if (mediaJobs == null || result.outputs() == null || result.outputs().isEmpty()) {
            return;
        }
        WorkflowRun run = workflowRunRepository.findById(result.runId())
                .orElseThrow(() -> new IllegalStateException("Workflow run disappeared: " + result.runId()));
        mediaJobs.registerOutputs(run.getOwnerId(), result.runId(), result.jobId(), stepOrder, attempt,
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
            applied = transactions.recordPickup(step.getRunId(), step.getId(), attempt, at, at.plus(lease),
                    at.plus(maxRuntime), deliveryCount);
        } else {
            Instant now = clock.instant();
            Short percent = progress.percent() == null ? null : progress.percent().shortValue();
            applied = transactions.extendLease(step.getRunId(), step.getId(), attempt, now, now.plus(lease), percent,
                    deliveryCount, now.plus(maxRuntime));
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
        inRunCalling(runId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T inRunCalling(UUID runId, Supplier<T> action) {
        try (StepLogContext ignored = StepLogContext.run(runId);
                BaggageInScope baggage = tracer.createBaggageInScope(BAGGAGE_RUN_ID, runId.toString())) {
            return Observation.createNotStarted(RUN_OBSERVATION, observations)
                    .highCardinalityKeyValue(BAGGAGE_RUN_ID, runId.toString())
                    .observe(action);
        }
    }
}
