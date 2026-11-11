package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.api.StepEdit;
import com.flowcortex.workflow.api.UpdateWorkflow;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import java.util.List;
import java.util.UUID;

/**
 * Workflow writes that know who the caller is. Lives outside {@code api} on purpose: only this module's controllers
 * may hand it an admin {@link Caller}.
 */
public interface WorkflowAuthoring {

    WorkflowView createDraft(UUID ownerId, String name, boolean shared);

    WorkflowView updateWorkflow(Caller caller, UUID workflowId, UpdateWorkflow update);

    void deleteWorkflow(Caller caller, UUID workflowId);

    WorkflowStepView addStep(Caller caller, UUID workflowId, NewStep step);

    WorkflowStepView updateStep(Caller caller, UUID workflowId, UUID stepId, StepEdit edit);

    void deleteStep(Caller caller, UUID workflowId, UUID stepId);

    WorkflowView reorderSteps(Caller caller, UUID workflowId, List<UUID> stepIds);
}
