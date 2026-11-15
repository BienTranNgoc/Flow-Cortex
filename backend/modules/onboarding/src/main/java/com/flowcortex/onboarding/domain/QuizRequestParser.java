package com.flowcortex.onboarding.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Parses a quiz body; unknown properties are ignored and one bad field refuses the whole body. */
public final class QuizRequestParser {

    private QuizRequestParser() {
    }

    /** @throws InvalidOnboardingRequest naming every bad field */
    public static QuizRequest parse(JsonNode body) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (body == null || !body.isObject()) {
            throw new InvalidOnboardingRequest("body", JsonFields.WRONG_TYPE);
        }
        QuizRequest request = new QuizRequest(
                JsonFields.list(body, "purposes", OnboardingVocabulary.PURPOSES, errors),
                JsonFields.scalar(body, "source", OnboardingVocabulary.SOURCES, errors),
                JsonFields.scalar(body, "aiLevel", OnboardingVocabulary.AI_LEVELS, errors),
                inVocabularyOrder(JsonFields.list(body, "industries", OnboardingVocabulary.INDUSTRIES, errors)),
                JsonFields.list(body, "contentTypes", OnboardingVocabulary.CONTENT_TYPES, errors),
                JsonFields.scalar(body, "usageType", OnboardingVocabulary.USAGE_TYPES, errors),
                JsonFields.flag(body, "finished", errors),
                JsonFields.flag(body, "skipped", errors));
        if (!errors.isEmpty()) {
            throw new InvalidOnboardingRequest(errors);
        }
        return request;
    }

    private static Answer<List<String>> inVocabularyOrder(Answer<List<String>> industries) {
        if (!(industries instanceof Answer.Value<List<String>> picked)) {
            return industries;
        }
        List<String> ordered = new ArrayList<>(picked.value());
        ordered.sort((a, b) -> Integer.compare(
                OnboardingVocabulary.INDUSTRIES.indexOf(a), OnboardingVocabulary.INDUSTRIES.indexOf(b)));
        return Answer.value(List.copyOf(ordered));
    }
}
