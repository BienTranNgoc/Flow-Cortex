package com.flowcortex.trash.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/**
 * One entry of the member's Trash. {@code name} and {@code location} are text in the request's language: an untitled
 * item carries the untitled text, and a location code without text in either language is shown as the code.
 */
@Schema(name = "TrashItem")
public record TrashItemView(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The owning module's item type key") String type,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The item's id within its type") String id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Where the item lived before it was deleted") String location,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant deletedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "When the sweep deletes the item for good") Instant expiresAt,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whole days to expiresAt, rounded up, never below 0",
                minimum = "0") int remainingDays) {
}
