package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds the destinations an imported workflow would send data to: a rule table from step type to the config fields
 * that name one. Every step type registered at the base whose config names an external destination belongs in
 * {@link #TABLE}; none does yet.
 */
final class OutboundTargets {

    /** One destination: the step, its type, the config field and the value, verbatim for a string else as JSON. */
    record Target(int stepOrder, String type, String field, String value) {
    }

    static final Map<String, List<String>> TABLE = Map.of();

    private static final ObjectMapper JSON = new ObjectMapper();

    private OutboundTargets() {
    }

    static List<Target> targetsOf(List<StepDefinition> steps, Map<String, List<String>> table) {
        List<Target> targets = new ArrayList<>();
        for (StepDefinition step : steps) {
            List<String> fields = table.get(step.type());
            if (fields == null || step.config() == null) {
                continue;
            }
            JsonNode config = parse(step.config());
            for (String field : fields) {
                JsonNode value = config.get(field);
                if (value != null && !value.isNull()) {
                    targets.add(new Target(step.stepOrder(), step.type(), field,
                            value.isTextual() ? value.asText() : value.toString()));
                }
            }
        }
        return List.copyOf(targets);
    }

    private static JsonNode parse(String config) {
        try {
            return JSON.readTree(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("workflow step config is not valid JSON", e);
        }
    }
}
