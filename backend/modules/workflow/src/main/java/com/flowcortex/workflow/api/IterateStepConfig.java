package com.flowcortex.workflow.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The config of an {@code iterate} step: the child step it runs per list item and the bounds it runs
 * them under. Faults name the field and never echo a caller's value.
 *
 * @param childConfig the child's config as compact JSON, {@code {}} when none was given
 * @param failFast {@code true} when {@code onItemError} is {@code fail}
 */
public record IterateStepConfig(String childType, String childConfig, int maxItems, int concurrency,
        boolean failFast) {

    public static final String TYPE = "iterate";

    /** A type joins only through its own change, which makes its guards read {@link #effectiveTypes}. */
    public static final Set<String> CHILD_TYPES = Set.of("echo", "fail", "translate", "tts");

    private static final int MIN_ITEMS = 1;
    private static final int MAX_ITEMS = 50;
    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 10;
    private static final int DEFAULT_CONCURRENCY = 3;

    private static final ObjectMapper READER = new ObjectMapper();
    private static final String ON_ERROR_CONTINUE = "continue";
    private static final String ON_ERROR_FAIL = "fail";
    private static final Set<String> KEYS = Set.of("child", "maxItems", "concurrency", "onItemError");
    private static final Set<String> CHILD_KEYS = Set.of("type", "config");
    private static final int MAX_KEY_ECHO = 40;
    private static final String SHAPE = "a JSON object with child, maxItems, concurrency and onItemError";
    private static final String NOT_AN_OBJECT = "config must be " + SHAPE;

    private record Parsed(IterateStepConfig config, String fault) {
    }

    /** @throws IllegalArgumentException with the fault naming the field when {@code configJson} is not valid */
    public static IterateStepConfig parse(String configJson) {
        Parsed parsed = read(configJson);
        if (parsed.fault() != null) {
            throw new IllegalArgumentException(parsed.fault());
        }
        return parsed.config();
    }

    /** What is wrong with {@code configJson}, or {@code null} when it is a valid iterate config. */
    public static String fault(String configJson) {
        return read(configJson).fault();
    }

    /**
     * The step types a step of {@code type} runs: for an iterate step with a readable config, itself
     * followed by {@code maxItems} copies of its child type; for any other step, or an unreadable
     * config, the type alone.
     */
    public static List<String> effectiveTypes(String type, String config) {
        if (!TYPE.equals(type)) {
            return List.of(type);
        }
        Parsed parsed = read(config);
        if (parsed.config() == null) {
            return List.of(type);
        }
        IterateStepConfig iterate = parsed.config();
        List<String> types = new ArrayList<>(iterate.maxItems() + 1);
        types.add(type);
        types.addAll(Collections.nCopies(iterate.maxItems(), iterate.childType()));
        return List.copyOf(types);
    }

    private static Parsed read(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return failed("config is required: " + SHAPE);
        }
        JsonNode root;
        try {
            root = READER.readTree(configJson);
        } catch (JsonProcessingException e) {
            return failed(NOT_AN_OBJECT);
        }
        if (root == null || !root.isObject()) {
            return failed(NOT_AN_OBJECT);
        }
        String unknown = unknownKey(root, KEYS);
        if (unknown != null) {
            return failed("'" + echo(unknown) + "' is not allowed; the keys are child, maxItems, concurrency and onItemError");
        }
        JsonNode child = root.get("child");
        if (child == null || !child.isObject()) {
            return failed("child is required: an object with type and an optional config");
        }
        String unknownChild = unknownKey(child, CHILD_KEYS);
        if (unknownChild != null) {
            return failed("'child." + echo(unknownChild) + "' is not allowed; child takes type and config only");
        }
        JsonNode type = child.get("type");
        if (type == null || !type.isTextual() || type.textValue().isBlank()) {
            return failed("child.type must be a non-empty string");
        }
        JsonNode childConfig = child.get("config");
        if (childConfig != null && !childConfig.isObject()) {
            return failed("child.config must be a JSON object");
        }
        Integer maxItems = bounded(root, "maxItems", MAX_ITEMS, MIN_ITEMS, MAX_ITEMS);
        if (maxItems == null) {
            return failed("maxItems must be a whole number from " + MIN_ITEMS + " to " + MAX_ITEMS);
        }
        Integer concurrency = bounded(root, "concurrency", DEFAULT_CONCURRENCY, MIN_CONCURRENCY, MAX_CONCURRENCY);
        if (concurrency == null) {
            return failed("concurrency must be a whole number from " + MIN_CONCURRENCY + " to " + MAX_CONCURRENCY);
        }
        JsonNode onItemError = root.get("onItemError");
        boolean failFast = false;
        if (root.has("onItemError")) {
            String value = onItemError.isTextual() ? onItemError.textValue() : null;
            if (!ON_ERROR_CONTINUE.equals(value) && !ON_ERROR_FAIL.equals(value)) {
                return failed("onItemError must be '" + ON_ERROR_CONTINUE + "' or '" + ON_ERROR_FAIL + "'");
            }
            failFast = ON_ERROR_FAIL.equals(value);
        }
        String config = childConfig == null ? "{}" : childConfig.toString();
        return new Parsed(new IterateStepConfig(type.textValue(), config, maxItems, concurrency, failFast), null);
    }

    /** The value of {@code key} when it is a whole number in range, the default when absent, else {@code null}. */
    private static Integer bounded(JsonNode root, String key, int defaultValue, int min, int max) {
        if (!root.has(key)) {
            return defaultValue;
        }
        JsonNode value = root.get(key);
        if (!value.isInt() || value.intValue() < min || value.intValue() > max) {
            return null;
        }
        return value.intValue();
    }

    private static String unknownKey(JsonNode object, Set<String> allowed) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private static Parsed failed(String fault) {
        return new Parsed(null, fault);
    }

    private static String echo(String name) {
        return name.length() <= MAX_KEY_ECHO ? name : name.substring(0, MAX_KEY_ECHO) + "...";
    }
}
