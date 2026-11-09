package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * Public API backing the read side of {@code /api/v1/workflows}. Every method applies the same visibility rule: a
 * workflow is visible when {@code ownerId = currentUserId} or shared — otherwise 404 ("not found", not "forbidden").
 */
public interface WorkflowService {

    List<WorkflowView> listVisibleWorkflows(UUID currentUserId);

    /** @throws NotFoundException if {@code workflowId} is not visible to {@code currentUserId} */
    WorkflowView getVisibleWorkflow(UUID currentUserId, UUID workflowId);

    /**
     * Creates a workflow definition with its ordered steps ({@code step_order} 1-based by list position). Not
     * exposed over HTTP (fixed-demo MVP) — seeder-only, like {@link com.flowcortex.user.api.UserService#createInitialAdmin}.
     * @throws jakarta.validation.ConstraintViolationException if a step names a type the engine cannot run, one
     *         violation per such step at {@code steps[i].type}; nothing is saved
     */
    WorkflowView createWorkflow(UUID ownerId, String name, boolean shared, List<NewWorkflowStep> steps);

    /** Creates an empty draft owned by {@code ownerId}. */
    WorkflowView createDraft(UUID ownerId, String name, boolean shared);

    /**
     * Changes a workflow the caller owns: 403 when the caller can see it but does not own it, 404 when it is
     * unknown, deleted or hidden from the caller.
     */
    WorkflowView updateWorkflow(UUID callerId, UUID workflowId, UpdateWorkflow update);

    /** Soft-deletes a workflow the caller owns; refusals as for {@link #updateWorkflow}. */
    void deleteWorkflow(UUID callerId, UUID workflowId);

    /** Appends a step at the next position; refusals as for {@link #updateWorkflow}, plus 400 and 422 on the step. */
    WorkflowStepView addStep(UUID callerId, UUID workflowId, NewStep step);

    /** Edits name, config and input reference of a step of the workflow; the type and position never change. */
    WorkflowStepView updateStep(UUID callerId, UUID workflowId, UUID stepId, StepEdit edit);

    /**
     * Removes a step and closes the gap: later steps move down one and every explicit reference keeps reading the
     * step it read. Refused with 409 while another step reads the deleted one; other refusals as for
     * {@link #updateWorkflow}, plus 404 for a step of another workflow and 400 when the result is not a valid
     * definition.
     */
    void deleteStep(UUID callerId, UUID workflowId, UUID stepId);

    /**
     * Puts the steps in the order of {@code stepIds}, which must list every step exactly once: 422 on a repeated id,
     * 409 on a missing or foreign one, 422 when a step would read a step at or after itself. References move with
     * their steps. Refusals as for {@link #deleteStep}.
     */
    WorkflowView reorderSteps(UUID callerId, UUID workflowId, List<UUID> stepIds);
}
