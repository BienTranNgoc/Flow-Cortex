package com.flowcortex.workflow.application;

import com.flowcortex.media.api.MediaAssetInput;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a run and its pending steps and announces it, in one transaction: a member's start opens it, a step that
 * starts a child run joins the transaction its claim already holds.
 */
@Component
class RunCreation {

    /** A run to create; the parent fields are null (and {@code callDepth} 0) for a run a member started. */
    record NewRun(UUID runId, UUID workflowId, UUID ownerId, List<WorkflowStep> steps, String input,
            String optionsJson, String stepInputJson, List<MediaAssetInput> copies, UUID parentRunId,
            UUID parentStepId, Short parentStepAttempt, short callDepth) {

        static NewRun started(UUID runId, UUID workflowId, UUID ownerId, List<WorkflowStep> steps, String input,
                String optionsJson, String stepInputJson, List<MediaAssetInput> copies) {
            return new NewRun(runId, workflowId, ownerId, steps, input, optionsJson, stepInputJson, copies, null,
                    null, null, (short) 0);
        }

        static NewRun child(UUID workflowId, UUID ownerId, List<WorkflowStep> steps, String input,
                String optionsJson, UUID parentRunId, UUID parentStepId, short parentStepAttempt, short callDepth) {
            return new NewRun(UUID.randomUUID(), workflowId, ownerId, steps, input, optionsJson, null, List.of(),
                    parentRunId, parentStepId, parentStepAttempt, callDepth);
        }
    }

    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final ApplicationEventPublisher events;
    private final RunUpdates runUpdates;
    private final ObjectProvider<StepExecutorRegistry> registry;
    private final MediaJobs mediaJobs;
    private final TransactionTemplate transactionTemplate;

    RunCreation(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            ApplicationEventPublisher events, RunUpdates runUpdates, ObjectProvider<StepExecutorRegistry> registry,
            MediaJobs mediaJobs, TransactionTemplate transactionTemplate) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.events = events;
        this.runUpdates = runUpdates;
        this.registry = registry;
        this.mediaJobs = mediaJobs;
        this.transactionTemplate = transactionTemplate;
    }

    RunView create(NewRun newRun) {
        StepExecutorRegistry steps = registry.getObject();
        return transactionTemplate.execute(status -> {
            WorkflowRun run = workflowRunRepository.save(toRun(newRun));
            if (!newRun.copies().isEmpty()) {
                mediaJobs.bindRunInputs(run.getId(), newRun.copies());
            }
            List<RunStep> runSteps = new ArrayList<>();
            for (WorkflowStep stepDef : newRun.steps()) {
                String stepInput = steps.isRemote(stepDef.getType()) ? newRun.stepInputJson() : null;
                runSteps.add(new RunStep(
                        UUID.randomUUID(),
                        run.getId(),
                        stepDef.getStepOrder(),
                        stepDef.getType(),
                        stepInput,
                        stepDef.getConfig(),
                        stepDef.getInputRef()));
            }
            runSteps = runStepRepository.saveAll(runSteps);
            runUpdates.touched(run.getId());

            // Published within this tx; RunAdvancer's @ApplicationModuleListener only
            // fires after commit — the caller returns first.
            events.publishEvent(new WorkflowRunStartedEvent(run.getId()));
            return WorkflowMapper.toRunView(run, runSteps, steps::isRemote, mediaJobs::isDownloadable);
        });
    }

    private static WorkflowRun toRun(NewRun newRun) {
        if (newRun.parentRunId() == null) {
            return new WorkflowRun(newRun.runId(), newRun.workflowId(), newRun.ownerId(), newRun.input(),
                    newRun.optionsJson(), Instant.now());
        }
        return WorkflowRun.child(newRun.runId(), newRun.workflowId(), newRun.ownerId(), newRun.input(),
                newRun.optionsJson(), Instant.now(), newRun.parentRunId(), newRun.parentStepId(),
                newRun.parentStepAttempt(), newRun.callDepth());
    }
}
