package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.RunStepView;
import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.List;
import java.util.Locale;

/** Entity -> {@code api} view mapping (design.md D1/D5), mirroring {@code AppUserMapper}'s role in {@code :modules:user}. */
final class WorkflowMapper {

    private WorkflowMapper() {
    }

    static WorkflowView toWorkflowView(Workflow workflow, List<WorkflowStep> steps) {
        return new WorkflowView(workflow.getId(), workflow.getOwnerId(), workflow.getName(), workflow.isShared(),
                workflow.getCreatedAt(), workflow.getUpdatedAt(),
                steps.stream().map(WorkflowMapper::toStepView).toList());
    }

    private static WorkflowStepView toStepView(WorkflowStep step) {
        return new WorkflowStepView(step.getId(), step.getStepOrder(), step.getName(), step.getType());
    }

    static RunView toRunView(WorkflowRun run, List<RunStep> steps) {
        return new RunView(run.getId(), run.getWorkflowId(), run.getOwnerId(), wire(run.getStatus().name()),
                run.getInput(), run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt(),
                steps.stream().map(WorkflowMapper::toRunStepView).toList());
    }

    private static RunStepView toRunStepView(RunStep step) {
        return new RunStepView(step.getId(), step.getStepOrder(), step.getType(), wire(step.getStatus().name()),
                step.getInput(), step.getOutput(), step.getErrorMessage(), step.getStartedAt(), step.getFinishedAt());
    }

    /** design.md D1: run/run_step status is wire-visible lowercase (running/completed/error/pending). */
    private static String wire(String enumName) {
        return enumName.toLowerCase(Locale.ROOT);
    }
}
