package com.flowcortex.user.accountdeletion;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A pending or expired deletion request as the session endpoint reports it. */
public record AccountDeletionView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AccountDeletionState state,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant requestedAt,
        @Schema(description = "When the grace period ends; the deletion can be cancelled until then",
                requiredMode = Schema.RequiredMode.REQUIRED) Instant scheduledAt) {
}
