package com.flowcortex.workflow.api;

import java.util.List;
import java.util.UUID;

/**
 * Public API backing the read side of {@code /api/v1/workflows} (design.md
 * D1/D3, R-049). Every method applies the SAME visibility rule: a workflow
 * is visible when {@code ownerId = currentUserId} OR the workflow is shared —
 * a workflow outside that is invisible (404), the same "cross-scope read is
 * not found, not forbidden" idiom {@code :modules:user}'s R-025 uses.
 */
public interface WorkflowService {

    List<WorkflowView> listVisibleWorkflows(UUID currentUserId);

    /**
     * @throws com.flowcortex.shared.web.NotFoundException if {@code
     *         workflowId} is not visible to {@code currentUserId}
     */
    WorkflowView getVisibleWorkflow(UUID currentUserId, UUID workflowId);

    /**
     * Creates a workflow definition with its ordered steps ({@code
     * step_order} assigned 1-based by list position, R-040). Not an
     * admin/user-facing HTTP operation — design.md's Open Questions notes "no
     * create-workflow path is exposed (fixed-demo MVP)" — this exists so the
     * dev-profile seeder (task 5.9) can seed the two demo workflows, the same
     * role {@link com.flowcortex.user.api.UserService#createInitialAdmin}
     * plays in {@code :modules:user}.
     */
    WorkflowView createWorkflow(UUID ownerId, String name, boolean shared, List<NewWorkflowStep> steps);
}
