package com.flowcortex.onboarding.web;

import com.flowcortex.onboarding.web.OnboardingChoices.ContentType;
import com.flowcortex.onboarding.web.OnboardingChoices.Industry;
import com.flowcortex.onboarding.web.OnboardingChoices.Purpose;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;

/** The caller's onboarding; an answer is null while unanswered or skipped. */
@Schema(name = "OnboardingStatusResponse")
record OnboardingStatusResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean completed,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time")
        Instant completedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"array", "null"})
        List<Purpose> purposes,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                allowableValues = {"facebook", "tiktok", "linkedin", "google", "referral", "other"})
        String source,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                allowableValues = {"beginner", "tried", "automation", "builder"})
        String aiLevel,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"array", "null"})
        List<Industry> industries,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"array", "null"})
        List<ContentType> contentTypes,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"},
                allowableValues = {"personal", "team"})
        String usageType,
        @Schema(requiredMode = RequiredMode.REQUIRED) OnboardingProgressView progress,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean firstRunDone,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean open,
        @Schema(requiredMode = RequiredMode.REQUIRED, types = {"string", "null"}, allowableValues = {"first"})
        String entry) {
}
