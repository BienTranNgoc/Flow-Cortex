package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code isShared} is what makes a workflow listable to every authenticated user. {@code estimatedCredits}
 * is the sum of its steps' configured costs; {@code null} while credits are switched off.
 */
record WorkflowResponse(
        UUID id, String name, boolean isShared, Instant createdAt, Instant updatedAt,
        List<WorkflowStepResponse> steps, Long estimatedCredits) {

    static WorkflowResponse from(WorkflowView view, CreditAccounts credits) {
        return new WorkflowResponse(view.id(), view.name(), view.shared(), view.createdAt(), view.updatedAt(),
                view.steps().stream().map(WorkflowStepResponse::from).toList(),
                credits.enabled() ? credits.estimate(view.steps().stream().map(WorkflowStepView::type).toList()) : null);
    }
}
