package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** design.md D1/R-049 — {@code isShared} is what makes a workflow listable to every authenticated user. */
record WorkflowResponse(
        UUID id, String name, boolean isShared, Instant createdAt, Instant updatedAt,
        List<WorkflowStepResponse> steps) {

    static WorkflowResponse from(WorkflowView view) {
        return new WorkflowResponse(view.id(), view.name(), view.shared(), view.createdAt(), view.updatedAt(),
                view.steps().stream().map(WorkflowStepResponse::from).toList());
    }
}
