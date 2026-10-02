package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.AdminRunView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code status} is the lowercase wire value: running/completed/error. {@code ownerEmail} is
 * {@code null} when the owner no longer exists; {@code errorMessage} is the engine's own text.
 */
record AdminRunResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String workflowName,
        String ownerEmail,
        @Schema(requiredMode = RequiredMode.REQUIRED) String status,
        String errorMessage,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant startedAt,
        Instant finishedAt) {

    static AdminRunResponse from(AdminRunView view) {
        return new AdminRunResponse(view.id(), view.workflowName(), view.ownerEmail(), view.status(),
                view.errorMessage(), view.startedAt(), view.finishedAt());
    }
}
