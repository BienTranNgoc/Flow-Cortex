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
        List<StepDef> defs = steps.stream()
                .map(step -> new StepDef(step.getStepOrder(), step.getName(), step.getType(), step.getConfig(),
                        step.getInputRef()))
                .toList();
        for (RunStartGuard guard : guards) {
            guard.check(ownerId, workflowId, defs, context);
        }
    }
}
