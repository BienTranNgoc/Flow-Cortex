package com.flowcortex.onboarding.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.Map;

/** Documents the builder progress body for the published contract only. */
@Schema(name = "BuilderProgressBody")
record BuilderProgressBody(
        @Schema(requiredMode = RequiredMode.REQUIRED, type = "object") Map<String, Object> state) {
}
