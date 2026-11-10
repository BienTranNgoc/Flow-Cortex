package com.flowcortex.user.audit;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Resolves which resource an admin write touched, and a human label for it. */
final class AuditResource {

    static final int MAX_ID_LENGTH = 100;
    static final int MAX_LABEL_LENGTH = 200;
    private static final List<String> LABEL_FIELDS = List.of("title", "name", "email", "slug");

    private AuditResource() {}

    record Result(String id, String label) {}

    /**
     * The id is the path variable named {@code resourceParam}, else the response field of that name,
     * else the response {@code id}. The label is the first non-blank of title, name, email, slug in
     * the response. An empty {@code resourceParam} skips the path and the named field and takes the
     * response {@code id}.
     */
    static Result of(String resourceParam, Map<String, String> pathVars, Object responseBody, JsonMapper mapper) {
        JsonNode response = responseBody == null ? null : mapper.valueToTree(responseBody);
        String id = null;
        if (!resourceParam.isEmpty()) {
            id = pathVars.get(resourceParam);
            if (id == null) {
                id = text(response, resourceParam);
            }
        }
        if (id == null) {
            id = text(response, "id");
        }
        String label = null;
        for (String field : LABEL_FIELDS) {
            label = text(response, field);
            if (label != null) {
                break;
            }
        }
        return new Result(cut(id, MAX_ID_LENGTH), cut(label, MAX_LABEL_LENGTH));
    }

    private static String text(JsonNode tree, String field) {
        if (tree == null || !tree.isObject()) {
            return null;
        }
        JsonNode value = tree.get(field);
        if (value == null || !value.isValueNode() || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text.isBlank() ? null : text;
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
