package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code isShared} is what makes a workflow listable to every authenticated user; while
 * {@code available} is false, starting a run of it answers 503.
 */
record WorkflowResponse(
        UUID id, String name, boolean isShared, Instant createdAt, Instant updatedAt,
        List<WorkflowStepResponse> steps, @Schema(requiredMode = RequiredMode.REQUIRED) boolean available) {

    static WorkflowResponse from(WorkflowView view) {
        return new WorkflowResponse(view.id(), view.name(), view.shared(), view.createdAt(), view.updatedAt(),
                view.steps().stream().map(WorkflowStepResponse::from).toList(), view.available());
    }
}
