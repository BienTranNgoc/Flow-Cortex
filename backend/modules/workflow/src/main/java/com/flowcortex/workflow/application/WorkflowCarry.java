package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one declaration of what a copied workflow step keeps: every persistent field of a step except its id and
 * workflow id. A fork and an import both write steps through {@link #copySteps}, and a test locks the declaration
 * against the entity, {@link StepDefinition}, the artifact's step keys and the version snapshot.
 */
final class WorkflowCarry {

    static final Set<String> CARRIED_STEP_FIELDS = Set.of("stepOrder", "name", "type", "config", "inputRef");

    private WorkflowCarry() {
    }

    /** New steps with fresh ids, belonging to {@code newWorkflowId}, in the order given. */
    static List<WorkflowStep> copySteps(List<StepDefinition> steps, UUID newWorkflowId) {
        return steps.stream()
                .map(step -> new WorkflowStep(UUID.randomUUID(), newWorkflowId, (short) step.stepOrder(), step.name(),
                        step.type(), step.inputRef(), step.config()))
                .toList();
    }

    static List<StepDefinition> of(List<WorkflowStep> steps) {
        return steps.stream()
                .map(step -> new StepDefinition(step.getStepOrder(), step.getName(), step.getType(), step.getConfig(),
                        step.getInputRef()))
                .toList();
    }
}
