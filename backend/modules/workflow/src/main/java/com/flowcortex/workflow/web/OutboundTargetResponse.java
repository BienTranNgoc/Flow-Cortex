package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** A destination a step of the reviewed file would send to, listed for the admin to acknowledge. */
record OutboundTargetResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) int stepOrder,
        @Schema(requiredMode = RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = RequiredMode.REQUIRED) String field,
        @Schema(requiredMode = RequiredMode.REQUIRED) String value) {
}
