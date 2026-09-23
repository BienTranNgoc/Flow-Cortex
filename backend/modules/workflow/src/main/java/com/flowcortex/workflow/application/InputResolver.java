package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Input resolution: step 1 resolves to the run's own input; step N defaults to the preceding
 * step's output, unless {@code input_ref} is {@code {step:K.field}}, in which case that field
 * is read out of step K's output (parsed as JSON). Any unresolved/incomplete reference (bad
 * format, unknown/forward step, missing output, non-JSON, missing field) throws rather than
 * returning empty, so the orchestrator turns it into a run error.
 *
 * <p>Builds its own plain {@link ObjectMapper} rather than injecting Spring Boot's
 * auto-configured one: parsing a step output needs none of that bean's customization, and Boot
 * 4.1 doesn't guarantee a consumer classpath alone registers it.
 */
@Component
class InputResolver {

    private static final Pattern REF_PATTERN = Pattern.compile("^\\{step:(\\d+)\\.(\\w+)}$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param stepOrder    1-based position of the step being resolved
     * @param inputRef     raw {@code input_ref}, or {@code null}/blank for the default (previous output)
     * @param runInput     the run's own input (used for step 1 only)
     * @param priorOutputs step_order -> output, for steps already completed in this run
     */
    String resolve(int stepOrder, String inputRef, String runInput, Map<Integer, String> priorOutputs) {
        if (stepOrder == 1) {
            return runInput;
        }
        if (inputRef != null && !inputRef.isBlank()) {
            return resolveRef(inputRef, priorOutputs);
        }
        int previousOrder = stepOrder - 1;
        // A null VALUE for an already-completed step is real data (e.g. an "echo" step given a
        // null input), not absence — containsKey is the only check that distinguishes that from
        // the real error case: step (stepOrder - 1) never completed at all.
        if (!priorOutputs.containsKey(previousOrder)) {
            throw new IllegalStateException("Step " + previousOrder + " produced no output to default from");
        }
        return priorOutputs.get(previousOrder);
    }

    private String resolveRef(String inputRef, Map<Integer, String> priorOutputs) {
        Matcher matcher = REF_PATTERN.matcher(inputRef);
        if (!matcher.matches()) {
            throw new IllegalStateException("Invalid input_ref format: " + inputRef);
        }
        int referencedOrder = Integer.parseInt(matcher.group(1));
        String field = matcher.group(2);
        String referencedOutput = priorOutputs.get(referencedOrder);
        if (referencedOutput == null) {
            throw new IllegalStateException(
                    "input_ref " + inputRef + " references step " + referencedOrder + " with no output available");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(referencedOutput);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "input_ref " + inputRef + " target step " + referencedOrder + " output is not valid JSON", e);
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalStateException(
                    "input_ref " + inputRef + " field '" + field + "' not found in step " + referencedOrder
                            + " output");
        }
        return value.isTextual() ? value.asText() : value.toString();
    }
}
