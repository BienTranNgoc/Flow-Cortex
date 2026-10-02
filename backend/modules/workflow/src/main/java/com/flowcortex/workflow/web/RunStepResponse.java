package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunStepOutputView;
import com.flowcortex.workflow.api.RunStepView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code status} is the exact lowercase wire value: pending/running/completed/error. A running
 * step with no {@code pickedUpAt} is still waiting to start.
 */
record RunStepResponse(
        UUID id, int stepOrder, String type, String status, String input, String output, String errorMessage,
        Instant startedAt, Instant finishedAt, Integer progressPercent,
        @Schema(requiredMode = RequiredMode.REQUIRED) int attempt, String errorCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) int deliveryCount, Instant pickedUpAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<RunStepOutputResponse> outputs,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"inline", "remote"}) String mode) {

    static RunStepResponse from(RunStepView view) {
        return new RunStepResponse(view.id(), view.stepOrder(), view.type(), view.status(), view.input(),
                view.output(), view.errorMessage(), view.startedAt(), view.finishedAt(), view.progressPercent(),
                view.attempt(), view.errorCode(), view.deliveryCount(), view.pickedUpAt(),
                view.outputs().stream().map(RunStepOutputResponse::from).toList(), view.mode());
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
