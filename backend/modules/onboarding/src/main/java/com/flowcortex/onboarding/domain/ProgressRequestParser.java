package com.flowcortex.onboarding.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Parses a progress body; every field is optional, but a present field may not be null. */
public final class ProgressRequestParser {

    private ProgressRequestParser() {
    }

    /** @throws InvalidOnboardingRequest naming every bad field */
    public static ProgressPatch parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new InvalidOnboardingRequest("body", JsonFields.WRONG_TYPE);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        String step = step(body, errors);
        Integer question = question(body, errors);
        boolean surveyDeferred = JsonFields.flag(body, "surveyDeferred", errors);
        boolean finishing = JsonFields.flag(body, "finished", errors);
        if (!errors.isEmpty()) {
            throw new InvalidOnboardingRequest(errors);
        }
        return new ProgressPatch(step, question, surveyDeferred, finishing);
    }

    private static String step(JsonNode body, Map<String, String> errors) {
        JsonNode node = body.get("step");
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            errors.put("step", JsonFields.NOT_NULL);
        } else if (!node.isString()) {
            errors.put("step", JsonFields.WRONG_TYPE);
        } else if (!OnboardingVocabulary.STEPS.contains(node.asString())) {
            errors.put("step", JsonFields.UNKNOWN_VALUE);
        } else {
            return node.asString();
        }
        return null;
    }

    private static Integer question(JsonNode body, Map<String, String> errors) {
        JsonNode node = body.get("question");
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            errors.put("question", JsonFields.NOT_NULL);
        } else if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            errors.put("question", node.isIntegralNumber() ? JsonFields.OUT_OF_RANGE : JsonFields.WRONG_TYPE);
        } else if (node.intValue() < OnboardingVocabulary.MIN_QUESTION
                || node.intValue() > OnboardingVocabulary.MAX_QUESTION) {
            errors.put("question", JsonFields.OUT_OF_RANGE);
        } else {
            return node.intValue();
        }
        return null;
    }
}
