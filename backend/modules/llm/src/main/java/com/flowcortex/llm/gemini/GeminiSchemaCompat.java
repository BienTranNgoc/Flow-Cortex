package com.flowcortex.llm.gemini;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Finds what Gemini refuses in a response schema with a 400: an {@code enum} holding a non-string,
 * {@code properties} on a node not typed object, {@code items} on a node not typed array. Paths name
 * fields the way a caller wrote them ({@code scenes.items.duration_sec}); {@code properties} is left out
 * of the path, tuple items and composite branches add their index ({@code items.0}, {@code anyOf.1}).
 */
final class GeminiSchemaCompat {

    private static final String ROOT = "(root)";
    private static final List<String> COMPOSITES = List.of("anyOf", "oneOf", "allOf");

    private GeminiSchemaCompat() {
    }

    /** One entry per violation, "path: reason"; empty when Gemini would take the schema. */
    static List<String> violations(JsonNode schema) {
        List<String> found = new ArrayList<>();
        walk(schema, "", found);
        return found;
    }

    private static void walk(JsonNode node, String path, List<String> found) {
        if (node == null || !node.isObject()) {
            return;
        }
        JsonNode enumeration = node.path("enum");
        if (enumeration.isArray() && containsNonString(enumeration)) {
            found.add(label(path) + ": enum holds a value that is not a string");
        }
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            if (!isTyped(node, "object")) {
                found.add(label(path) + ": properties on a node not typed object");
            }
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                walk(property.getValue(), join(path, property.getKey()), found);
            }
        }
        JsonNode items = node.path("items");
        if (items.isObject() || items.isArray()) {
            if (!isTyped(node, "array")) {
                found.add(label(path) + ": items on a node not typed array");
            }
            if (items.isArray()) {
                walkEach(items, join(path, "items"), found);
            } else {
                walk(items, join(path, "items"), found);
            }
        }
        for (String composite : COMPOSITES) {
            JsonNode branches = node.path(composite);
            if (branches.isArray()) {
                walkEach(branches, join(path, composite), found);
            }
        }
    }

    private static void walkEach(JsonNode list, String path, List<String> found) {
        for (int i = 0; i < list.size(); i++) {
            walk(list.get(i), join(path, String.valueOf(i)), found);
        }
    }

    private static boolean containsNonString(JsonNode values) {
        for (JsonNode value : values) {
            if (!value.isString()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTyped(JsonNode node, String type) {
        JsonNode declared = node.path("type");
        if (declared.isString()) {
            return declared.asString().equalsIgnoreCase(type);
        }
        if (declared.isArray()) {
            for (JsonNode candidate : declared) {
                if (candidate.isString() && candidate.asString().equalsIgnoreCase(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    private static String label(String path) {
        return path.isEmpty() ? ROOT : path;
    }
}
