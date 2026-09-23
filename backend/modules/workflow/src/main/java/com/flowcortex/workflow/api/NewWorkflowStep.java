package com.flowcortex.workflow.api;

/**
 * One step to create as part of {@link WorkflowService#createWorkflow}.
 * {@code inputRef}, when set, is the raw {@code {step:K.field}} reference
 * text; {@code null} defaults to the previous step's output (or, for step 1,
 * the run's own input).
 */
public record NewWorkflowStep(String name, String type, String inputRef) {
}
