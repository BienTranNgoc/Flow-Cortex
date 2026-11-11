package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.MediaOutput;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunStartGuard.StepDef;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.application.RunResumeRules.Kind;
import com.flowcortex.workflow.application.RunResumeRules.Refused;
import com.flowcortex.workflow.application.RunResumeRules.StepState;
import com.flowcortex.workflow.application.RunResumeRules.Verdict;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A member getting a failed run going again from one of its steps: retry re-arms the failed step as
 * its next attempt and continues the run; reset re-arms it and leaves the run stopped; execute
 * starts the first step that has not completed. Which operation is allowed is decided by {@link
 * RunResumeRules}; each write is a conditional {@code UPDATE} taken under the run's row lock, so two
 * callers racing on one failure have exactly one winner. Dispatch happens after commit, through
 * {@link WorkflowRunResumedEvent}.
 */
@Service
public class RunResumption {

    private static final Logger LOG = LoggerFactory.getLogger(RunResumption.class);

    private enum Operation {
        RETRY("retry"), RESET("reset"), EXECUTE("execute");

        private final String logName;

        Operation(String logName) {
            this.logName = logName;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper OUTPUTS_READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<MediaOutput>> OUTPUTS = new TypeReference<>() { };

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final StepExecutorRegistry registry;
    private final MediaJobs mediaJobs;
    private final TransactionTemplate transactionTemplate;
    private final RunUpdates runUpdates;
    private final RunStartGuards runStartGuards;
    private final ApplicationEventPublisher events;
    private final boolean workflowEnabled;

    RunResumption(WorkflowRepository workflowRepository, WorkflowRunRepository workflowRunRepository,
            RunStepRepository runStepRepository, StepExecutorRegistry registry, MediaJobs mediaJobs,
            TransactionTemplate transactionTemplate, RunUpdates runUpdates, RunStartGuards runStartGuards,
            ApplicationEventPublisher events, @Value("${flowcortex.workflow.enabled:true}") boolean workflowEnabled) {
        this.workflowRepository = workflowRepository;
        this.runStartGuards = runStartGuards;
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.registry = registry;
        this.mediaJobs = mediaJobs;
        this.transactionTemplate = transactionTemplate;
        this.runUpdates = runUpdates;
        this.events = events;
        this.workflowEnabled = workflowEnabled;
    }

    /**
     * Runs the failed step again as its next attempt, in the same run, which continues to its end.
     * @throws NotFoundException if the run or the step is not the caller's
     * @throws ConflictException if the run or step is not in a state the operation accepts, or the run's input files expired
     * @throws LocalizedStatusException 503 when workflows, the media engine or dubbing are switched off
     */
    public RunView retryStep(UUID callerId, UUID runId, UUID stepId) {
        return resume(Operation.RETRY, callerId, runId, stepId);
    }

    /** Puts the failed step back to pending as its next attempt; nothing runs and the run stays failed. */
    public RunView resetStep(UUID callerId, UUID runId, UUID stepId) {
        return resume(Operation.RESET, callerId, runId, stepId);
    }

    /** Starts the run's first step that has not completed, re-opening the run when it is failed. */
    public RunView executeStep(UUID callerId, UUID runId, UUID stepId) {
        return resume(Operation.EXECUTE, callerId, runId, stepId);
    }

    private RunView resume(Operation operation, UUID callerId, UUID runId, UUID stepId) {
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
        List<RunStep> steps = runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        List<StepState> states = steps.stream()
                .map(step -> new StepState(step.getId(), step.getStepOrder(), step.getType(), step.getStatus(),
                        step.getErrorCode(), step.getAttempt()))
                .toList();

        Verdict verdict = operation == Operation.EXECUTE
                ? RunResumeRules.forExecute(run.getStatus(), states, stepId)
                : RunResumeRules.forRetry(run.getStatus(), states, stepId);
        if (verdict instanceof Refused refused) {
            LOG.info("Run resume: op={} outcome=refused kind={} runId={} stepId={}", operation.logName, refused.kind(),
                    runId, stepId);
            throw refusal(refused.kind(), stepId);
        }

        RunStep step = steps.stream().filter(candidate -> candidate.getId().equals(stepId)).findFirst().orElseThrow();
        List<MediaJobInput> bound = mediaJobs.boundRunInputs(runId).stream().map(MediaAssetInput::input).toList();
        if (operation != Operation.RESET) {
            requireAvailable(steps);
            requireInputsStored(runId, bound, steps);
        }
        // A claim overwrites a step's stored input with the resolved one, so it is rebuilt, never kept.
        String restoredInput = registry.isRemote(step.getType()) ? RunInputShape.storedInput(bound) : null;
        short attempt = step.getAttempt();

        transactionTemplate.executeWithoutResult(status -> {
            workflowRunRepository.lockById(runId);
            if (operation != Operation.EXECUTE
                    && runStepRepository.reArm(stepId, runId, attempt, restoredInput) == 0) {
                logRefusal(operation, "step_not_failed", runId, stepId, attempt);
                throw new ConflictException(new LocalizedMessage("workflow.runStep.notFailed"));
            }
            boolean reopen = operation == Operation.RETRY
                    || (operation == Operation.EXECUTE && run.getStatus() == RunStatus.ERROR);
            if (reopen && workflowRunRepository.reopen(runId, callerId) == 0) {
                logRefusal(operation, "run_not_reopened", runId, stepId, attempt);
                throw operation == Operation.RETRY
                        ? new ConflictException(new LocalizedMessage("workflow.run.notFailed"))
                        : new ConflictException(new LocalizedMessage("workflow.run.notResumable"));
            }
            runUpdates.touched(runId);
            if (operation != Operation.RESET) {
                events.publishEvent(new WorkflowRunResumedEvent(runId));
            }
        });
        LOG.info("Run resume: op={} outcome=resumed runId={} stepId={} attempt={}", operation.logName, runId, stepId,
                operation == Operation.EXECUTE ? attempt : attempt + 1);
        return currentView(callerId, runId);
    }

    /**
     * Retries a finished run as a new run: the steps before {@code fromStepOrder} (default: the first that did
     * not complete) are carried over completed with their stored objects copied, the rest run again. The
     * original run is left as it was.
     * @throws NotFoundException if the run is not the caller's, or its workflow is no longer visible to them
     * @throws ConflictException if the run is not finished, or a stored object it needs is gone
     * @throws UnprocessableEntityException if {@code fromStepOrder} is not a valid start
     * @throws LocalizedStatusException 400 for a Product Video run, 503 when workflows or the media engine are off
     */
    public RunView retryRun(UUID callerId, UUID runId, Integer fromStepOrder, boolean acknowledgeCost) {
        if (!workflowEnabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.disabled"));
        }
        WorkflowRun original = workflowRunRepository.findByIdAndOwnerId(runId, callerId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("workflow.run.notFound", runId)));
        if (ProductVideoWorkflow.ID.equals(original.getWorkflowId())) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.run.retry.productVideo"));
        }
        List<RunStep> steps = runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        List<StepState> states = steps.stream()
                .map(step -> new StepState(step.getId(), step.getStepOrder(), step.getType(), step.getStatus(),
                        step.getErrorCode(), step.getAttempt()))
                .toList();
        Verdict verdict = RunResumeRules.forRunRetry(original.getStatus(), states, fromStepOrder);
        if (verdict instanceof Refused refused) {
            LOG.info("Run resume: op=retryRun outcome=refused kind={} runId={}", refused.kind(), runId);
            throw runRetryRefusal(refused.kind());
        }
        int from = ((RunResumeRules.Allowed) verdict).fromStepOrder();

        workflowRepository.findVisibleTo(original.getWorkflowId(), callerId)
                .orElseThrow(() -> new NotFoundException(
                        new LocalizedMessage("workflow.notFound", original.getWorkflowId())));
        List<RunStep> reusedSteps = steps.stream().filter(step -> step.getStepOrder() < from).toList();
        List<RunStep> tail = steps.stream().filter(step -> step.getStepOrder() >= from).toList();
        requireAvailableToRun(tail);
        runStartGuards.checkDefs(callerId, original.getWorkflowId(), tail.stream()
                .map(step -> new StepDef(step.getStepOrder(), step.getType(), step.getType(), step.getConfig(),
                        step.getInputRef()))
                .toList(), new StartContext(acknowledgeCost));

        UUID newRunId = UUID.randomUUID();
        List<MediaAssetInput> originalInputs = mediaJobs.boundRunInputs(runId);
        List<MediaAssetInput> copies = copyRunInputs(newRunId, originalInputs);
        String tailInput = RunInputShape.storedInput(copies.stream().map(MediaAssetInput::input).toList());
        Map<UUID, String> reusedOutputs = new HashMap<>();
        Map<UUID, List<MediaOutput>> reusedCopies = new HashMap<>();
        for (RunStep step : reusedSteps) {
            List<MediaOutput> stored = storedOutputs(step);
            if (stored.isEmpty()) {
                continue;
            }
            List<MediaOutput> copied = copyOutputs(runId, newRunId, step.getStepOrder(), stored);
            reusedCopies.put(step.getId(), copied);
            reusedOutputs.put(step.getId(), write(copied));
        }

        String options = original.getOptions();
        RunView view = transactionTemplate.execute(status -> {
            WorkflowRun created = workflowRunRepository.save(new WorkflowRun(newRunId, original.getWorkflowId(),
                    callerId, original.getInput(), options, Instant.now()));
            if (!copies.isEmpty()) {
                mediaJobs.bindRunInputs(newRunId, copies);
            }
            List<RunStep> carried = new ArrayList<>();
            Map<UUID, UUID> newIds = new HashMap<>();
            for (RunStep step : reusedSteps) {
                UUID newStepId = UUID.randomUUID();
                newIds.put(step.getId(), newStepId);
                carried.add(RunStep.reused(newStepId, newRunId, step.getStepOrder(), step.getType(), step.getInput(),
                        step.getConfig(), step.getInputRef(), step.getOutput(), reusedOutputs.get(step.getId()),
                        step.getStartedAt(), step.getFinishedAt()));
            }
            for (RunStep step : tail) {
                carried.add(new RunStep(UUID.randomUUID(), newRunId, step.getStepOrder(), step.getType(),
                        registry.isRemote(step.getType()) ? tailInput : null, step.getConfig(), step.getInputRef()));
            }
            List<RunStep> saved = runStepRepository.saveAll(carried);
            for (RunStep step : reusedSteps) {
                List<MediaOutput> copied = reusedCopies.get(step.getId());
                if (copied != null && registry.isRemote(step.getType())) {
                    mediaJobs.registerOutputs(callerId, newRunId, newIds.get(step.getId()), step.getStepOrder(), (short) 1,
                            copied);
                }
            }
            runUpdates.touched(newRunId);
            events.publishEvent(new WorkflowRunStartedEvent(newRunId));
            return WorkflowMapper.toRunView(created, saved, registry::isRemote, mediaJobs::isDownloadable);
        });
        LOG.info("Run resume: op=retryRun outcome=created runId={} newRunId={} fromStepOrder={}", runId, newRunId, from);
        return view;
    }

    private List<MediaAssetInput> copyRunInputs(UUID newRunId, List<MediaAssetInput> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        try {
            return mediaJobs.copyRunInputs(newRunId, inputs);
        } catch (UploadRejectedException e) {
            throw new ConflictException(new LocalizedMessage("workflow.run.retry.objectGone", 0));
        }
    }

    private List<MediaOutput> copyOutputs(UUID fromRunId, UUID toRunId, int stepOrder, List<MediaOutput> stored) {
        try {
            return mediaJobs.copyStepOutputs(fromRunId, toRunId, stepOrder, stored);
        } catch (UploadRejectedException e) {
            throw new ConflictException(new LocalizedMessage("workflow.run.retry.objectGone", stepOrder));
        }
    }

    private static List<MediaOutput> storedOutputs(RunStep step) {
        if (step.getOutputs() == null || step.getOutputs().isBlank()) {
            return List.of();
        }
        try {
            return OUTPUTS_READER.readValue(step.getOutputs(), OUTPUTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored outputs of step " + step.getId() + " are not readable", e);
        }
    }

    private static String write(List<MediaOutput> outputs) {
        try {
            return JSON.writeValueAsString(outputs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Step outputs could not be serialized", e);
        }
    }

    private RuntimeException runRetryRefusal(Kind kind) {
        return switch (kind) {
            case RUN_NOT_FINISHED -> new ConflictException(new LocalizedMessage("workflow.run.notFinished"));
            case FROM_INVALID -> new UnprocessableEntityException(new LocalizedMessage("workflow.run.retry.fromInvalid"),
                    "WORKFLOW_RETRY_FROM_INVALID",
                    Map.of("fromStepOrder", new LocalizedMessage("workflow.run.retry.fromInvalid")));
            default -> new IllegalStateException(kind + " is not a verdict of a run retry");
        };
    }

    private void requireAvailable(List<RunStep> steps) {
        if (!workflowEnabled) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("workflow.disabled"));
        }
        requireAvailableToRun(steps.stream().filter(step -> step.getStatus() != RunStepStatus.COMPLETED).toList());
    }

    private void requireAvailableToRun(List<RunStep> remaining) {
        if (remaining.stream().anyMatch(step -> registry.isRemote(step.getType())) && !mediaJobs.isEnabled()) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("media.disabled"));
        }
        if (remaining.stream().anyMatch(step -> !registry.isAvailable(step.getType()))) {
            throw new DubDisabledException();
        }
    }

    /** Run inputs expire; a remote step that would read an expired one fails after being charged, so refuse first. */
    private void requireInputsStored(UUID runId, List<MediaJobInput> bound, List<RunStep> steps) {
        boolean remoteLeft = steps.stream()
                .anyMatch(step -> step.getStatus() != RunStepStatus.COMPLETED && registry.isRemote(step.getType()));
        if (!bound.isEmpty() && remoteLeft && !mediaJobs.runInputsStored(runId)) {
            throw new ConflictException(new LocalizedMessage("workflow.run.retry.objectGone", 0));
        }
    }

    private RuntimeException refusal(Kind kind, UUID stepId) {
        return switch (kind) {
            case STEP_NOT_FOUND -> new NotFoundException(new LocalizedMessage("workflow.runStep.notFound", stepId));
            case RUN_NOT_FAILED -> new ConflictException(new LocalizedMessage("workflow.run.notFailed"));
            case RUN_NOT_RESUMABLE -> new ConflictException(new LocalizedMessage("workflow.run.notResumable"));
            case STEP_NOT_FAILED -> new ConflictException(new LocalizedMessage("workflow.runStep.notFailed"));
            case STEP_NOT_PENDING -> new ConflictException(new LocalizedMessage("workflow.runStep.notPending"));
            case STEP_NOT_NEXT -> new ConflictException(new LocalizedMessage("workflow.runStep.notNext"));
            case STEP_NOT_RETRYABLE -> new ConflictException(new LocalizedMessage("workflow.runStep.notRetryable"));
            case ATTEMPT_LIMIT -> new ConflictException(new LocalizedMessage("workflow.runStep.attemptLimit"));
            case RUN_NOT_FINISHED, FROM_INVALID ->
                    new IllegalStateException(kind + " is not a verdict of a step operation");
        };
    }

    private static void logRefusal(Operation operation, String outcome, UUID runId, UUID stepId, short attempt) {
        LOG.info("Run resume: op={} outcome={} runId={} stepId={} attempt={}", operation.logName, outcome, runId,
                stepId, attempt);
    }

    private RunView currentView(UUID callerId, UUID runId) {
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, callerId).orElseThrow();
        return WorkflowMapper.toRunView(run, runStepRepository.findByRunIdOrderByStepOrderAsc(runId),
                registry::isRemote, mediaJobs::isDownloadable);
    }
}
