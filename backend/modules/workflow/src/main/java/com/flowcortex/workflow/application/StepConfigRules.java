package com.flowcortex.workflow.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Save-time config rules, one per step type: a pure function from the config text to the fault that
 * refuses it, or {@code null}. A type with no entry accepts any config.
 */
final class StepConfigRules {

    private static final Map<String, Function<String, String>> RULES = rules();

    private StepConfigRules() {
    }

    private static Map<String, Function<String, String>> rules() {
        Map<String, Function<String, String>> rules = new LinkedHashMap<>();
        rules.put("connector.call", ConnectorCallConfig::fault);
        return Map.copyOf(rules);
    }

    /** What is wrong with {@code config} for a step of {@code type}, or {@code null}. */
    static String fault(String type, String config) {
        Function<String, String> rule = type == null ? null : RULES.get(type);
        return rule == null ? null : rule.apply(config);
    }
}
