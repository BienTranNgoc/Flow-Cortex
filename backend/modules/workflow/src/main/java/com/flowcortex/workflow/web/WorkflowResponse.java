package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.WorkflowView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code isShared} is what makes a workflow listable to every authenticated user. {@code estimatedCredits}
 * is the sum of its steps' configured costs; {@code null} while credits are switched off. While
 * {@code available} is false, starting a run of it answers 503. {@code servedVersion} is the number of the
 * version {@code steps} come from, null when they are the live draft; {@code publishedVersionId} is the pointer.
 */
record WorkflowResponse(
        UUID id, String name, boolean isShared, Instant createdAt, Instant updatedAt,
        List<WorkflowStepResponse> steps, Long estimatedCredits,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean available,
        @Schema(allowableValues = {"draft", "published"}) String status,
        @Schema(nullable = true) UUID publishedVersionId, @Schema(nullable = true) Integer servedVersion) {

    static WorkflowResponse from(WorkflowView view, CreditAccounts credits) {
        return new WorkflowResponse(view.id(), view.name(), view.shared(), view.createdAt(), view.updatedAt(),
                view.steps().stream().map(WorkflowStepResponse::from).toList(),
                view.estimatedCredits(credits), view.available(), view.status(), view.publishedVersionId(),
                view.servedVersion());
    }
}
