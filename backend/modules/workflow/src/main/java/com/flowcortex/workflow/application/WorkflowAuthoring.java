package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.NewStep;
import com.flowcortex.workflow.api.StepEdit;
import com.flowcortex.workflow.api.UpdateWorkflow;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import java.util.UUID;

/**
 * Workflow writes that know who the caller is. Lives outside {@code api} on purpose: only this module's controllers
 * may hand it an admin {@link Caller}.
 */
public interface WorkflowAuthoring {

    /** Creates an empty private draft owned by {@code ownerId}. */
    WorkflowView createDraft(UUID ownerId, String name);

    WorkflowView updateWorkflow(Caller caller, UUID workflowId, UpdateWorkflow update);

    void deleteWorkflow(Caller caller, UUID workflowId);

    WorkflowStepView addStep(Caller caller, UUID workflowId, NewStep step);

    WorkflowStepView updateStep(Caller caller, UUID workflowId, UUID stepId, StepEdit edit);
}
