package com.flowcortex.onboarding.web;

import com.flowcortex.onboarding.web.OnboardingChoices.ContentType;
import com.flowcortex.onboarding.web.OnboardingChoices.Industry;
import com.flowcortex.onboarding.web.OnboardingChoices.Purpose;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Documents the quiz body for the published contract only; the route reads the raw JSON so that an
 * absent field (keep) and an explicit null (skipped) stay distinct. Every field is optional.
 */
@Schema(name = "OnboardingQuizBody")
record OnboardingQuizBody(
        @Schema(types = {"array", "null"})
        List<Purpose> purposes,
        @Schema(types = {"string", "null"}, allowableValues = {"facebook", "tiktok", "linkedin", "google",
                "referral", "other"})
        String source,
        @Schema(types = {"string", "null"}, allowableValues = {"beginner", "tried", "automation", "builder"})
        String aiLevel,
        @Schema(types = {"array", "null"})
        List<Industry> industries,
        @Schema(types = {"array", "null"})
        List<ContentType> contentTypes,
        @Schema(types = {"string", "null"}, allowableValues = {"personal", "team"})
        String usageType,
        Boolean finished,
        Boolean skipped) {
}
