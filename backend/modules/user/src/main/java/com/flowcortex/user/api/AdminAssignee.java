package com.flowcortex.user.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A staff account that can be offered as an assignee. */
@Schema(name = "AdminAssignee")
public record AdminAssignee(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {
}
