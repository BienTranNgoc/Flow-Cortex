package com.flowcortex.onboarding.web;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documents the progress body for the published contract only; every field is optional and none accepts null. */
@Schema(name = "OnboardingProgressBody")
record OnboardingProgressBody(
        @Schema(allowableValues = {"welcome", "tour:home", "survey-intro", "quiz", "done"})
        String step,
        @Schema(minimum = "1", maximum = "6")
        Integer question,
        Boolean surveyDeferred,
        Boolean finished) {
}
