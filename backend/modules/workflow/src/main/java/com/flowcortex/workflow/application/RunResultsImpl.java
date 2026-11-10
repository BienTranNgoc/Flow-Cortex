package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaJob;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.RunResultView;
import com.flowcortex.workflow.api.RunResults;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.RunWithWorkflowName;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class RunResultsImpl implements RunResults {

    private static final Logger LOG = LoggerFactory.getLogger(RunResultsImpl.class);

    /** Sorts before every real run id in the database's uuid order. */
    private static final UUID FROM_THE_START = new UUID(0L, 0L);

    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final StepExecutorRegistry registry;
    private final MediaJobs mediaJobs;

    RunResultsImpl(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            StepExecutorRegistry registry, MediaJobs mediaJobs) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.registry = registry;
        this.mediaJobs = mediaJobs;
    }

    @Override
    public Optional<RunResultView> completedRun(UUID runId) {
        return workflowRunRepository.findCompletedWithWorkflowName(runId).map(this::toView);
    }

    @Override
    public List<RunResultView> recentCompletedRuns(UUID ownerId, int limit) {
        requirePositive(limit);
        return workflowRunRepository.findCompletedByOwnerNewestFirst(ownerId, PageRequest.of(0, limit)).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public List<UUID> completedRunIds(UUID afterId, int limit) {
        requirePositive(limit);
        return workflowRunRepository.findCompletedIdsAfter(afterId == null ? FROM_THE_START : afterId,
                PageRequest.of(0, limit));
    }

    @Override
    public Optional<String> stepOutput(UUID ownerId, UUID runId, int stepOrder) {
        return workflowRunRepository.findCompletedWithWorkflowName(runId)
                .filter(found -> found.run().getOwnerId().equals(ownerId))
                .flatMap(found -> runStepRepository.findByRunIdOrderByStepOrderAsc(runId).stream()
                        .filter(step -> step.getStepOrder() == stepOrder)
                        .findFirst())
                .map(RunStep::getOutput);
    }

    private RunResultView toView(RunWithWorkflowName found) {
        UUID runId = found.run().getId();
        List<RunStep> steps = runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        boolean firstStepRemote = !steps.isEmpty() && registry.isRemote(steps.getFirst().getType());
        return new RunResultView(runId, found.run().getOwnerId(), found.workflowName(),
                firstStepRemote ? null : found.run().getInput(), found.run().getFinishedAt(),
                steps.stream().map(step -> toStepView(runId, step)).toList());
    }

    private RunResultView.Step toStepView(UUID runId, RunStep step) {
        String prefix = MediaJob.outputPrefix(runId, step.getStepOrder());
        List<RunResultView.Output> outputs = WorkflowMapper.downloadableOutputs(step, mediaJobs::isDownloadable)
                .stream()
                .filter(output -> liesBelow(prefix, runId, step, output))
                .map(output -> new RunResultView.Output(output.name(), output.bucket(), output.objectKey(),
                        output.mime(), output.bytes()))
                .toList();
        return new RunResultView.Step(step.getId(), step.getStepOrder(), step.getType(),
                registry.isRemote(step.getType()) ? "remote" : "inline",
                step.getStatus().name().toLowerCase(Locale.ROOT), step.getOutput(), step.getFinishedAt(), outputs);
    }

    /** An output whose key is missing or outside its step's storage prefix is never handed to a caller. */
    private static boolean liesBelow(String prefix, UUID runId, RunStep step, WorkflowMapper.StoredOutput output) {
        if (output.objectKey() != null && MediaJob.isBelow(prefix, output.objectKey())) {
            return true;
        }
        LOG.warn("Stored output left out of the run result, it lies outside its step: runId={} stepOrder={} name={}",
                runId, step.getStepOrder(), output.name());
        return false;
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
    }
}
