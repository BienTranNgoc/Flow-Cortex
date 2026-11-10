package com.flowcortex.onboarding.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** The stored journey progress; a member who never started has {@code {}}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "OnboardingProgress")
record OnboardingProgressView(
        Integer v,
        @Schema(allowableValues = {"welcome", "tour:home", "survey-intro", "quiz", "done"}) String step,
        @Schema(minimum = "1", maximum = "6") Integer question,
        @Schema(format = "date-time") String surveyDeferredAt,
        @Schema(format = "date-time") String finishedAt,
        @Schema(format = "date-time") String updatedAt) {

    static OnboardingProgressView of(JsonNode progress) {
        return new OnboardingProgressView(
                integer(progress, "v"), text(progress, "step"), integer(progress, "question"),
                text(progress, "surveyDeferredAt"), text(progress, "finishedAt"), text(progress, "updatedAt"));
    }

    private static Integer integer(JsonNode progress, String field) {
        JsonNode node = progress.get(field);
        return node != null && node.isIntegralNumber() ? node.intValue() : null;
    }

    private static String text(JsonNode progress, String field) {
        JsonNode node = progress.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }
}
