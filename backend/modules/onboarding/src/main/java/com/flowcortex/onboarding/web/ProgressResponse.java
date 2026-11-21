package com.flowcortex.onboarding.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/** The stored progress after a write; {@code completedAt} is present only when the write finished the journey. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "OnboardingProgressResponse")
record ProgressResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) OnboardingProgressView progress,
        Instant completedAt) {
}
