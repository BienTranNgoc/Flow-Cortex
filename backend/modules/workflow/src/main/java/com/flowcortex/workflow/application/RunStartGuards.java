package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.RunStartGuard;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunStartGuard.StepDef;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/** The one place that decides which start checks run and in what order. */
@Component
class RunStartGuards {

    private final List<RunStartGuard> guards;

    @Autowired
    RunStartGuards(ObjectProvider<RunStartGuard> guards) {
        this(guards.stream().toList());
    }

    RunStartGuards(List<RunStartGuard> guards) {
        List<RunStartGuard> ordered = new ArrayList<>(guards);
        AnnotationAwareOrderComparator.sort(ordered);
        this.guards = List.copyOf(ordered);
    }

    void check(UUID ownerId, UUID workflowId, List<WorkflowStep> steps, StartContext context) {
        checkDefs(ownerId, workflowId, steps.stream()
                .map(step -> new StepDef(step.getStepOrder(), step.getName(), step.getType(), step.getConfig(),
                        step.getInputRef()))
                .toList(), context);
    }

    /** Runs every guard over {@code defs}, which may be only the steps a run retry re-executes. */
    void checkDefs(UUID ownerId, UUID workflowId, List<StepDef> defs, StartContext context) {
        for (RunStartGuard guard : guards) {
            guard.check(ownerId, workflowId, defs, context);
        }
    }

    /**
     * Validates structural readiness against the full workflow while charging/checking per-step guards
     * only for the single step being regenerated.
     */
    void checkSingleStep(UUID ownerId, UUID workflowId, List<WorkflowStep> allSteps, WorkflowStep targetStep,
            StartContext context) {
        checkSubset(ownerId, workflowId, allSteps, List.of(targetStep), context);
    }

    /**
     * Validates structural readiness against the full workflow while charging/checking per-step guards
     * only for the subset of steps being re-executed.
     */
    void checkSubset(UUID ownerId, UUID workflowId, List<WorkflowStep> allSteps, List<WorkflowStep> targetSteps,
            StartContext context) {
        List<StepDef> allDefs = allSteps.stream()
                .map(step -> new StepDef(step.getStepOrder(), step.getName(), step.getType(), step.getConfig(),
                        step.getInputRef()))
                .toList();
        List<StepDef> subsetDefs = targetSteps.stream()
                .map(step -> new StepDef(step.getStepOrder(), step.getName(), step.getType(), step.getConfig(),
                        step.getInputRef()))
                .toList();
        for (RunStartGuard guard : guards) {
            guard.check(ownerId, workflowId, guard instanceof WorkflowReadinessGuard ? allDefs : subsetDefs, context);
        }
    }
}
