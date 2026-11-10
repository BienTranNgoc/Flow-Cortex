package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.api.WorkflowVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code visibility} says who besides the owner can read it: {@code public} workflows are listable to every
 * authenticated user. {@code estimatedCredits} is the sum of its steps' configured costs; {@code null} while
 * credits are switched off. While {@code available} is false, starting a run of it answers 503.
 */
record WorkflowResponse(
        UUID id, String name, WorkflowVisibility visibility, Instant createdAt, Instant updatedAt,
        List<WorkflowStepResponse> steps, Long estimatedCredits,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean available,
        @Schema(allowableValues = {"draft", "published"}) String status) {

    static WorkflowResponse from(WorkflowView view, CreditAccounts credits) {
        return new WorkflowResponse(view.id(), view.name(), view.visibility(), view.createdAt(), view.updatedAt(),
                view.steps().stream().map(WorkflowStepResponse::from).toList(),
                credits.enabled() ? credits.estimate(view.steps().stream().map(WorkflowStepView::type).toList()) : null,
                view.available(), view.status());
    }
}
