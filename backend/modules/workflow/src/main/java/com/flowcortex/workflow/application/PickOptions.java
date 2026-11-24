package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recognises reviewed content that is a list of options to pick from: a JSON array of two or more
 * objects, or an object with exactly one key holding such an array. Pure: nothing is stored, the
 * list is a function of the content.
 *
 * <p>Builds its own plain {@link ObjectMapper} as {@link InputResolver} does; trailing text after
 * the JSON value makes the content plain text, not a list.
 */
final class PickOptions {

    private static final int MIN_OPTIONS = 2;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private PickOptions() {}

    static Optional<List<JsonNode>> detect(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        JsonNode candidate = root;
        if (root.isObject() && root.size() == 1) {
            candidate = root.elements().next();
        }
        if (!candidate.isArray() || candidate.size() < MIN_OPTIONS) {
            return Optional.empty();
        }
        List<JsonNode> options = new ArrayList<>(candidate.size());
        for (JsonNode element : candidate) {
            if (!element.isObject()) {
                return Optional.empty();
            }
            options.add(element);
        }
        return Optional.of(List.copyOf(options));
    }

    /** The chosen option as compact JSON text; an index outside the list is a caller error. */
    static String forward(List<JsonNode> options, int index) {
        if (index < 0 || index >= options.size()) {
            throw new IllegalArgumentException("Option " + index + " is outside the " + options.size() + " offered");
        }
        try {
            return MAPPER.writeValueAsString(options.get(index));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A parsed option could not be written back as JSON", e);
        }
    }
}
