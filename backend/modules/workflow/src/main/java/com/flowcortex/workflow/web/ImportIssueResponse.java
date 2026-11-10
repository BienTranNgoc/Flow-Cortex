package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** One finding of an import review; {@code stepOrder} and {@code field} are null when it is about the workflow. */
record ImportIssueResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"blocking", "advisory"}) String severity,
        @Schema(nullable = true) Integer stepOrder, @Schema(nullable = true) String field,
        @Schema(requiredMode = RequiredMode.REQUIRED) String message) {
}
