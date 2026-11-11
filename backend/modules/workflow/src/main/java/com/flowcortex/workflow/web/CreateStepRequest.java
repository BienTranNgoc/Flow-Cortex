package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** A step to append; {@code config}, when set, must be a JSON object. */
record CreateStepRequest(
        @NotBlank String type, @Size(max = 255) String name, @Size(max = 255) String inputRef,
        @Schema(type = "object") JsonNode config) {
}
