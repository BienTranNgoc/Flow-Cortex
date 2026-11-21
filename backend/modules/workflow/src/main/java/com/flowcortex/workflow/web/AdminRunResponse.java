package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.AdminRunView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code status} is the lowercase wire value: running/waiting_approval/cancelling/cancelled/
 * completed/error. {@code ownerEmail} is {@code null} when the owner no longer exists;
 * {@code errorMessage} is the engine's own text; {@code creditsCharged} is debits minus refunds, and
 * {@code null} while credits are switched off.
 */
record AdminRunResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID workflowId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String workflowName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID ownerId,
        String ownerEmail,
        @Schema(requiredMode = RequiredMode.REQUIRED) String status,
        String errorMessage,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant startedAt,
        Instant finishedAt,
        Long creditsCharged) {

    static AdminRunResponse from(AdminRunView view) {
        return new AdminRunResponse(view.id(), view.workflowId(), view.workflowName(), view.ownerId(),
                view.ownerEmail(), view.status(), view.errorMessage(), view.startedAt(), view.finishedAt(),
                view.creditsCharged());
    }
}
