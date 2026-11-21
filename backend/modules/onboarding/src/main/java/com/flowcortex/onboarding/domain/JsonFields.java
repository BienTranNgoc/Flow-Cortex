package com.flowcortex.onboarding.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Field readers shared by the request parsers; each records a bundle key in {@code errors} on refusal. */
final class JsonFields {

    static final String UNKNOWN_VALUE = "onboarding.field.unknownValue";
    static final String WRONG_TYPE = "onboarding.field.wrongType";
    static final String EMPTY_LIST = "onboarding.field.emptyList";
    static final String DUPLICATE = "onboarding.field.duplicate";
    static final String TOO_MANY = "onboarding.field.tooMany";
    static final String NOT_NULL = "onboarding.field.notNull";
    static final String OUT_OF_RANGE = "onboarding.field.outOfRange";

    private JsonFields() {
    }

    /** Absent when missing, skipped when null, else a member of {@code allowed}. */
    static Answer<String> scalar(JsonNode body, String field, List<String> allowed, Map<String, String> errors) {
        JsonNode node = body.get(field);
        if (node == null) {
            return Answer.absent();
        }
        if (node.isNull()) {
            return Answer.skipped();
        }
        if (!node.isString()) {
            errors.put(field, WRONG_TYPE);
            return Answer.absent();
        }
        if (!allowed.contains(node.asString())) {
            errors.put(field, UNKNOWN_VALUE);
            return Answer.absent();
        }
        return Answer.value(node.asString());
    }

    /** Absent when missing, skipped when null, else a non-empty list of distinct members of {@code allowed}. */
    static Answer<List<String>> list(JsonNode body, String field, List<String> allowed, Map<String, String> errors) {
        JsonNode node = body.get(field);
        if (node == null) {
            return Answer.absent();
        }
        if (node.isNull()) {
            return Answer.skipped();
        }
        if (!node.isArray()) {
            errors.put(field, WRONG_TYPE);
            return Answer.absent();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isString()) {
                errors.put(field, WRONG_TYPE);
                return Answer.absent();
            }
            items.add(element.asString());
        }
        String refusal = listRefusal(items, allowed);
        if (refusal != null) {
            errors.put(field, refusal);
            return Answer.absent();
        }
        return Answer.value(List.copyOf(items));
    }

    private static String listRefusal(List<String> items, List<String> allowed) {
        if (items.size() > allowed.size()) {
            return TOO_MANY;
        }
        if (items.isEmpty()) {
            return EMPTY_LIST;
        }
        if (!allowed.containsAll(items)) {
            return UNKNOWN_VALUE;
        }
        Set<String> seen = new HashSet<>();
        return items.stream().allMatch(seen::add) ? null : DUPLICATE;
    }

    /** False when missing; null and non-boolean values are refused. */
    static boolean flag(JsonNode body, String field, Map<String, String> errors) {
        JsonNode node = body.get(field);
        if (node == null) {
            return false;
        }
        if (node.isNull()) {
            errors.put(field, NOT_NULL);
            return false;
        }
        if (!node.isBoolean()) {
            errors.put(field, WRONG_TYPE);
            return false;
        }
        return node.booleanValue();
    }
}
