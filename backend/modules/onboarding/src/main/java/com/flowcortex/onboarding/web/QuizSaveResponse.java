package com.flowcortex.onboarding.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** The quiz write's outcome; {@code skipped} is present only when the write was a skip. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "QuizSaveResponse")
record QuizSaveResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean completed,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time")
        Instant completedAt,
        Boolean skipped) {
}
