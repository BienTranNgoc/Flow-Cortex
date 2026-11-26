package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunStepChildView;
import com.flowcortex.workflow.api.RunStepFailure;
import com.flowcortex.workflow.api.RunStepOutputView;
import com.flowcortex.workflow.api.RunStepView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code status} is the exact lowercase wire value: pending/running/completed/error. A running
 * step with no {@code pickedUpAt} is still waiting to start. {@code failure} is {@code null} unless
 * the step is in {@code error}.
 */
record RunStepResponse(
        UUID id, int stepOrder, String type, String status, String input, String output, String errorMessage,
        Instant startedAt, Instant finishedAt, Integer progressPercent,
        @Schema(requiredMode = RequiredMode.REQUIRED) int attempt, String errorCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) int deliveryCount, Instant pickedUpAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<RunStepOutputResponse> outputs,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"inline", "remote"}) String mode,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<RunStepChildResponse> children,
        RunStepFailureResponse failure) {

    static RunStepResponse from(RunStepView view) {
        return new RunStepResponse(view.id(), view.stepOrder(), view.type(), view.status(), view.input(),
                view.output(), view.errorMessage(), view.startedAt(), view.finishedAt(), view.progressPercent(),
                view.attempt(), view.errorCode(), view.deliveryCount(), view.pickedUpAt(),
                view.outputs().stream().map(RunStepOutputResponse::from).toList(), view.mode(),
                view.children().stream().map(RunStepChildResponse::from).toList(),
                view.failure() == null ? null : RunStepFailureResponse.from(view.failure()));
    }

    /** Enum values only, never the step's message; the allowed values are the wire values of the failure table. */
    record RunStepFailureResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"validation", "handoff",
                    "configuration", "contract", "review_rejected", "insufficient_credits", "timeout",
                    "provider_error", "quota_exceeded", "unavailable", "processing", "internal"}) String category,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"user", "provider", "system"})
            String source,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean retryable,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"retry", "top_up", "change_input",
                    "edit_workflow", "none"}) String recovery) {

        static RunStepFailureResponse from(RunStepFailure failure) {
            return new RunStepFailureResponse(failure.category(), failure.source(), failure.retryable(),
                    failure.recovery());
        }
    }

    /** One item of an iterate step in the attempt the step is in now; {@code attempt} is the item's own. */
    record RunStepChildResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) int itemIndex,
            @Schema(requiredMode = RequiredMode.REQUIRED) String status, String output, String errorCode,
            String errorMessage, @Schema(requiredMode = RequiredMode.REQUIRED) int attempt, Instant startedAt,
            Instant finishedAt, @Schema(requiredMode = RequiredMode.REQUIRED) List<RunStepOutputResponse> outputs) {

        static RunStepChildResponse from(RunStepChildView view) {
            return new RunStepChildResponse(view.id(), view.itemIndex(), view.status(), view.output(),
                    view.errorCode(), view.errorMessage(), view.attempt(), view.startedAt(), view.finishedAt(),
                    view.outputs().stream().map(RunStepOutputResponse::from).toList());
        }
    }

    /** An object the step produced, by name; bucket and object key never leave the server. */
    record RunStepOutputResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = RequiredMode.REQUIRED) String mime,
            @Schema(requiredMode = RequiredMode.REQUIRED) long bytes) {

        static RunStepOutputResponse from(RunStepOutputView view) {
            return new RunStepOutputResponse(view.name(), view.mime(), view.bytes());
        }
    }
}
