package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowStepView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

record WorkflowStepResponse(
        UUID id, int stepOrder, String name, String type,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"inline", "remote"}) String mode) {

    static WorkflowStepResponse from(WorkflowStepView view) {
        return new WorkflowStepResponse(view.id(), view.stepOrder(), view.name(), view.type(), view.mode());
    }
}
