package com.flowcortex.onboarding.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** The recommended track keys for the caller; the app owns the wording in each language. */
@Schema(name = "OnboardingResultResponse")
record OnboardingResultResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean completed,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"marketing", "sales", "finance", "hr", "tech", "other"})
        String industry,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"marketing", "sales", "finance", "hr", "tech", "other"})
        String track,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                allowableValues = {"beginner", "tried", "automation", "builder"})
        String aiLevel,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"personal", "team"})
        String usageType) {
}
