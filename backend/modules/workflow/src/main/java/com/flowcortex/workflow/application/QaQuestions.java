package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shape a QA review's questions must have before anyone sees them. {@link #parse} keeps a
 * model's answer only when every rule holds; any break returns empty, and the caller falls back
 * to the standard questions, which are stored as {@link #STANDARD_MARKER} and worded per request.
 * Pure: no I/O.
 */
final class QaQuestions {

    /** Stored in place of a question list when the task uses the standard questions. */
    static final String STANDARD_MARKER = "{\"standard\":true}";

    static final int MAX_QUESTIONS = 5;
    static final int MAX_QUESTION_LENGTH = 300;
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 6;
    static final int MAX_OPTION_LENGTH = 120;

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,20}");

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private QaQuestions() {}

    enum Type {
        SINGLE,
        MULTI,
        TEXT;

        /** The wire name used in stored and generated JSON. */
        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Type> fromWire(String wire) {
            for (Type type : values()) {
                if (type.wire().equals(wire)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    /** A question as shown to the reviewer; {@code options} is empty for {@link Type#TEXT}. */
    record QaQuestion(String id, Type type, String question, List<String> options) {

        QaQuestion {
            options = List.copyOf(options);
        }

        boolean isChoice() {
            return type != Type.TEXT;
        }
    }

    /** The questions of a stored or generated JSON array, or empty when any rule is broken. */
    static Optional<List<QaQuestion>> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (!root.isArray() || root.isEmpty() || root.size() > MAX_QUESTIONS) {
            return Optional.empty();
        }
        List<QaQuestion> questions = new ArrayList<>(root.size());
        Set<String> ids = new HashSet<>();
        for (JsonNode element : root) {
            Optional<QaQuestion> question = question(element);
            if (question.isEmpty() || !ids.add(question.get().id())) {
                return Optional.empty();
            }
            questions.add(question.get());
        }
        return Optional.of(List.copyOf(questions));
    }

    /** True when the stored questions are the standard marker rather than a question list. */
    static boolean isStandard(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return root.isObject() && root.size() == 1 && root.path("standard").isBoolean()
                    && root.path("standard").booleanValue();
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private static Optional<QaQuestion> question(JsonNode node) {
        if (!node.isObject()) {
            return Optional.empty();
        }
        String id = text(node.get("id"));
        Optional<Type> type = Type.fromWire(text(node.get("type")));
        String question = text(node.get("question"));
        if (id == null || !ID.matcher(id).matches() || type.isEmpty() || !fits(question, MAX_QUESTION_LENGTH)) {
            return Optional.empty();
        }
        Optional<List<String>> options = options(type.get(), node.get("options"));
        return options.map(list -> new QaQuestion(id, type.get(), question, list));
    }

    /** A text question takes no options (null, absent or an empty list is none); a choice takes 2-6 distinct. */
    private static Optional<List<String>> options(Type type, JsonNode node) {
        boolean none = node == null || node.isNull() || (node.isArray() && node.isEmpty());
        if (type == Type.TEXT) {
            return none ? Optional.of(List.of()) : Optional.empty();
        }
        if (node == null || !node.isArray() || node.size() < MIN_OPTIONS || node.size() > MAX_OPTIONS) {
            return Optional.empty();
        }
        Set<String> seen = new HashSet<>();
        List<String> options = new ArrayList<>(node.size());
        for (JsonNode option : node) {
            String value = text(option);
            if (!fits(value, MAX_OPTION_LENGTH) || !seen.add(value)) {
                return Optional.empty();
            }
            options.add(value);
        }
        return Optional.of(options);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    /** Non-blank and at most {@code max} characters (code points, not UTF-16 units). */
    private static boolean fits(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max;
    }
}
