package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowStepView;
import java.util.UUID;

record WorkflowStepResponse(UUID id, int stepOrder, String name, String type) {

    static WorkflowStepResponse from(WorkflowStepView view) {
        return new WorkflowStepResponse(view.id(), view.stepOrder(), view.name(), view.type());
    }
}
