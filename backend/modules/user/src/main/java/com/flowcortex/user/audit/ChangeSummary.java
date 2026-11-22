package com.flowcortex.user.audit;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reduces a request body to the declared fields. Nothing undeclared is ever copied, so a secret in
 * the body cannot reach the audit row.
 */
final class ChangeSummary {

    static final int MAX_ITEMS = 50;
    static final int MAX_BYTES = 4096;
    private static final Logger LOG = LoggerFactory.getLogger(ChangeSummary.class);

    private ChangeSummary() {}

    /**
     * A declared field counts only when the body carries it with a non-null value: a typed record
     * writes the fields a client left out as null. Declared path parameters are put over the result.
     * Returns null when nothing declared is present or the result exceeds {@link #MAX_BYTES}.
     */
    static JsonNode build(
            Object body, String action, String[] capture, Map<String, String> declaredParams, JsonMapper mapper) {
        ObjectNode summary = mapper.createObjectNode();
        JsonNode tree = body == null ? null : mapper.valueToTree(body);
        if (tree != null && tree.isObject()) {
            pick(tree, capture, summary);
        } else if (tree != null && tree.isArray()) {
            ArrayNode items = mapper.createArrayNode();
            for (int i = 0; i < Math.min(tree.size(), MAX_ITEMS); i++) {
                JsonNode element = tree.get(i);
                if (!element.isObject()) {
                    continue;
                }
                ObjectNode picked = mapper.createObjectNode();
                pick(element, capture, picked);
                if (!picked.isEmpty()) {
                    items.add(picked);
                }
            }
            if (!items.isEmpty()) {
                summary.set("items", items);
                if (tree.size() > MAX_ITEMS) {
                    summary.put("truncated", true);
                }
            }
        }
        declaredParams.forEach(summary::put);
        if (summary.isEmpty()) {
            return null;
        }
        int bytes = mapper.writeValueAsString(summary).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) {
            LOG.warn("change summary dropped for {}: {} bytes", action, bytes);
            return null;
        }
        return summary;
    }

    private static void pick(JsonNode source, String[] capture, ObjectNode target) {
        for (String field : capture) {
            JsonNode value = source.get(field);
            if (value != null && !value.isNull()) {
                target.set(field, value);
            }
        }
    }
}
