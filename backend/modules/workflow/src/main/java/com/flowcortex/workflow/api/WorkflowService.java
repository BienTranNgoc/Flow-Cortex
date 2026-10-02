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
}
