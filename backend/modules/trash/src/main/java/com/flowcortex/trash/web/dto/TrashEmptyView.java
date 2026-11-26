package com.flowcortex.trash.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** What one empty call did: its counts and where to resume. */
@Schema(name = "TrashEmptyResult")
public record TrashEmptyView(
        @Schema(requiredMode = RequiredMode.REQUIRED) long purged,
        @Schema(requiredMode = RequiredMode.REQUIRED) long blocked,
        @Schema(requiredMode = RequiredMode.REQUIRED) long failed,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Entries still in the Trash after this call") long remaining,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "Send back as next to continue; null when every entry has been examined")
        String next) {
}
