package com.flowcortex.workflow.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Per-type config rules checked when a workflow is saved: a step type with an entry has its config
 * judged by that rule, a type without one is unconstrained.
 */
final class StepConfigRules {

    private static final Map<String, Function<String, String>> RULES = rules();

    private StepConfigRules() {
    }

    /** The fault the rule for {@code type} finds in {@code config}, or {@code null}. */
    static String fault(String type, String config) {
        Function<String, String> rule = RULES.get(type);
        return rule == null ? null : rule.apply(config);
    }

    private static Map<String, Function<String, String>> rules() {
        Map<String, Function<String, String>> rules = new LinkedHashMap<>();
        rules.put("workflow.call", WorkflowCallConfig::fault);
        return Collections.unmodifiableMap(rules);
    }
}
