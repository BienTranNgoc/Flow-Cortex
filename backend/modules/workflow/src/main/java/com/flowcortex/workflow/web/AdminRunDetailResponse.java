package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.AdminRunDetailView;
import com.flowcortex.workflow.api.AdminRunStepView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A run with its steps for staff. It has no input, output or outputs at any level: those are the
 * member's content. {@code creditsCharged} is {@code null} while credits are switched off.
 */
record AdminRunDetailResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID workflowId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String workflowName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID ownerId,
        String ownerEmail,
        @Schema(requiredMode = RequiredMode.REQUIRED) String status,
        String errorMessage,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant startedAt,
        Instant finishedAt,
        Long creditsCharged,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Step> steps) {

    /** {@code mode} is {@code inline} or {@code remote}; {@code progressPercent} is set while a remote step reports it. */
    record Step(
            @Schema(requiredMode = RequiredMode.REQUIRED) int stepOrder,
            @Schema(requiredMode = RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = RequiredMode.REQUIRED) String mode,
            @Schema(requiredMode = RequiredMode.REQUIRED) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) int attempt,
            @Schema(requiredMode = RequiredMode.REQUIRED) int deliveryCount,
            Integer progressPercent,
            String errorCode,
            String errorMessage,
            Instant pickedUpAt,
            Instant startedAt,
            Instant finishedAt) {

        static Step from(AdminRunStepView view) {
            return new Step(view.stepOrder(), view.type(), view.mode(), view.status(), view.attempt(),
                    view.deliveryCount(), view.progressPercent(), view.errorCode(), view.errorMessage(),
                    view.pickedUpAt(), view.startedAt(), view.finishedAt());
        }
    }

    static AdminRunDetailResponse from(AdminRunDetailView view) {
        return new AdminRunDetailResponse(view.id(), view.workflowId(), view.workflowName(), view.ownerId(),
                view.ownerEmail(), view.status(), view.errorMessage(), view.startedAt(), view.finishedAt(),
                view.creditsCharged(), view.steps().stream().map(Step::from).toList());
    }
}
