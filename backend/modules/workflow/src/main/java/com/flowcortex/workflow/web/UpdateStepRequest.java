package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** A partial change: an absent or null field stays, an empty {@code name} or {@code inputRef} clears it. */
record UpdateStepRequest(
        @Size(max = 255) String name, @Size(max = 255) String inputRef, @Schema(type = "object") JsonNode config) {
}
