package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * {@code value} is any JSON, a JSON {@code null} included; {@code dataType} is left out to keep an existing entry's type
 * (a new entry is {@code json}).
 */
record PutDataEntryRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Any JSON value", implementation = Object.class) JsonNode value,
        @Schema(nullable = true) String dataType) {
}
