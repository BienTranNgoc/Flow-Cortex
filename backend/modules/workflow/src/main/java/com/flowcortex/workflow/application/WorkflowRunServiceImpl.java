package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.media.api.MediaDownload;
import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.media.api.UploadRejectedException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.user.api.UserDirectory;
import com.flowcortex.workflow.api.AdminRunView;
import com.flowcortex.workflow.api.MediaJobView;
import com.flowcortex.workflow.api.RunOutputUrlView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.RunWithWorkflowName;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implementation of {@link WorkflowRunService}. {@link #startRun} is tx#1 of the async
 * after-commit execution model: creates the {@code WorkflowRun} + its {@code pending} {@code
 * RunStep}s, commits, and publishes {@link WorkflowRunStartedEvent} — {@link
 * RunAdvancer} picks it up AFTER commit and drives execution, so this method returns before
 * any step runs.
 *
 * <p>A run whose first step processes media starts from the caller's upload: the object is looked
 * up under a key derived from the caller and the upload id — never a bucket or key the client
 * sent — and that reference becomes the step's stored input, the only one it is dispatched with.
 */
@Service
class WorkflowRunServiceImpl implements WorkflowRunService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRunServiceImpl.class);

    /** Kept out of a displayed file name: control and format characters, and path separators. */
    private static final Pattern UNSAFE_IN_FILE_NAME = Pattern.compile("[\\p{Cc}\\p{Cf}/\\\\]");
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private static final ObjectMapper OUTPUTS_READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final ApplicationEventPublisher events;
    private final StepExecutorRegistry registry;
    private final MediaJobs mediaJobs;
    private final UserDirectory userDirectory;
    private final TransactionTemplate transactionTemplate;
    private final boolean workflowEnabled;

    WorkflowRunServiceImpl(WorkflowRepository workflowRepository, WorkflowStepRepository workflowStepRepository,
            WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            ApplicationEventPublisher events, StepExecutorRegistry registry, MediaJobs mediaJobs,
            UserDirectory userDirectory, TransactionTemplate transactionTemplate,
            @Value("${flowcortex.workflow.enabled:true}") boolean workflowEnabled) {
        this.workflowRepository = workflowRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.events = events;
        this.registry = registry;
        this.mediaJobs = mediaJobs;
        this.userDirectory = userDirectory;
        this.transactionTemplate = transactionTemplate;
        this.workflowEnabled = workflowEnabled;
    }

    @Override
    public RunView startRun(UUID currentUserId, UUID workflowId, String input) {
        return startRun(currentUserId, workflowId, input, null, null);
    }

    // Not @Transactional: the checks and the object-store lookup run before the run's own
    // transaction opens, so a slow store never holds a database connection.
    @Override
    public RunView startRun(UUID currentUserId, UUID workflowId, String input, UUID uploadId, String originalName) {
        // Kill switch — checked BEFORE anything is persisted: off means no
        // run is created at all, not a run that is immediately refused.
        if (!workflowEnabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Workflow execution is currently disabled");
        }

        Workflow workflow = workflowRepository.findVisibleTo(workflowId, currentUserId)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + workflowId));
        List<WorkflowStep> stepDefs = workflowStepRepository.findByWorkflowIdOrderByStepOrderAsc(workflow.getId());
        // Refused up front: a run whose remote step would only fail media_disabled later.
        if (stepDefs.stream().anyMatch(step -> registry.isRemote(step.getType())) && !mediaJobs.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media engine is currently disabled");
        }

        boolean mediaWorkflow = !stepDefs.isEmpty() && registry.isRemote(stepDefs.getFirst().getType());
        String runInput = input;
        String remoteStepInput = null;
        if (mediaWorkflow) {
            if (uploadId == null) {
                throw new UploadRejectedException("uploadId", "Choose a video or audio file to upload");
            }
            remoteStepInput = json(mediaJobs.resolveUpload(currentUserId, uploadId));
            runInput = displayName(originalName);
        }

        String displayedInput = runInput;
        String storedRemoteInput = remoteStepInput;
        return transactionTemplate.execute(status -> {
            WorkflowRun run = workflowRunRepository.save(new WorkflowRun(UUID.randomUUID(), workflow.getId(),
                    currentUserId, displayedInput, Instant.now()));
            List<RunStep> runSteps = new ArrayList<>();
            for (WorkflowStep stepDef : stepDefs) {
                String stepInput = registry.isRemote(stepDef.getType()) ? storedRemoteInput : null;
                runSteps.add(new RunStep(
                        UUID.randomUUID(),
                        run.getId(),
                        stepDef.getStepOrder(),
                        stepDef.getType(),
                        stepInput));
            }
            runSteps = runStepRepository.saveAll(runSteps);

            // Published within this tx; RunAdvancer's @ApplicationModuleListener only
            // fires after commit — this method returns first.
            events.publishEvent(new WorkflowRunStartedEvent(run.getId()));
            return WorkflowMapper.toRunView(run, runSteps, registry::isRemote);
        });
    }

    private String json(MediaJobInput reference) {
        try {
            return objectMapper.writeValueAsString(reference);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An object reference could not be serialized", e);
        }
    }

    /** The member's file name as it may be shown back: nothing that could act as a path or a control, at most 255 characters. */
    private static String displayName(String originalName) {
        if (originalName == null) {
            return null;
        }
        String cleaned = UNSAFE_IN_FILE_NAME.matcher(originalName).replaceAll("");
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_FILE_NAME_LENGTH) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_FILE_NAME_LENGTH));
        }
        return cleaned.isEmpty() ? null : cleaned;
    }

    @Override
    @Transactional(readOnly = true)
    public RunView getRun(UUID currentUserId, UUID runId) {
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, currentUserId)
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        List<RunStep> steps = runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        return WorkflowMapper.toRunView(run, steps, registry::isRemote);
    }

    @Override
    @Transactional(readOnly = true)
    public RunOutputUrlView getOutputUrl(UUID currentUserId, UUID runId, int stepOrder, String name) {
        workflowRunRepository.findByIdAndOwnerId(runId, currentUserId)
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        RunStep step = runStepRepository.findByRunIdOrderByStepOrderAsc(runId).stream()
                .filter(candidate -> candidate.getStepOrder() == stepOrder)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Step " + stepOrder + " not found in run " + runId));
        StoredOutput output = storedOutputs(step).stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Step " + stepOrder + " has no output named " + name));
        if (!MediaJob.isBelow(MediaJob.outputPrefix(runId, stepOrder), output.objectKey())) {
            LOG.warn("Stored output not signed, it lies outside its step: runId={} stepOrder={} name={} bucket={} key={}",
                    runId, stepOrder, name, output.bucket(), output.objectKey());
            throw new NotFoundException("Step " + stepOrder + " has no output named " + name);
        }
        MediaDownload download = mediaJobs.presignedGet(output.bucket(), output.objectKey(), output.name());
        return new RunOutputUrlView(download.url(), download.expiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<AdminRunView> listAllRuns(int page, int size, String status) {
        Page<RunWithWorkflowName> runs = workflowRunRepository.findAllNewestFirst(
                status == null ? null : RunStatus.valueOf(status.toUpperCase(Locale.ROOT)), PageRequest.of(page, size));
        // One lookup for the whole page, not one per row.
        Map<UUID, String> emails = userDirectory.findEmailsByIds(
                runs.stream().map(row -> row.run().getOwnerId()).collect(Collectors.toSet()));
        return new PageView<>(runs.stream()
                .map(row -> WorkflowMapper.toAdminRunView(row.run(), row.workflowName(),
                        emails.get(row.run().getOwnerId())))
                .toList(), page, size, runs.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<MediaJobView> listRemoteSteps(int page, int size, String status) {
        Page<RunStep> steps = runStepRepository.findByTypeNewestFirst(registry.remoteTypes(),
                status == null ? null : RunStepStatus.valueOf(status.toUpperCase(Locale.ROOT)),
                PageRequest.of(page, size));
        return new PageView<>(steps.stream().map(WorkflowMapper::toMediaJobView).toList(), page, size,
                steps.getTotalElements());
    }

    private List<StoredOutput> storedOutputs(RunStep step) {
        if (step.getOutputs() == null) {
            return List.of();
        }
        try {
            return List.of(OUTPUTS_READER.readValue(step.getOutputs(), StoredOutput[].class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("run_step " + step.getId() + " holds outputs that are not an output list", e);
        }
    }

    /** Where a step's output is stored; this never leaves the server. */
    private record StoredOutput(String name, String bucket, String objectKey) {
    }
}
