package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Input resolution: step 1 always resolves to the run's own input; step N
 * defaults to the immediately preceding step's output, unless the step
 * definition carries an {@code input_ref} of the form {@code {step:K.field}},
 * in which case the named field is read out of step K's output (parsed as
 * JSON). An unresolved or incomplete reference — bad format, an
 * unknown/forward step, a missing output, non-JSON output, or a missing
 * field — throws rather than returning an empty string, so the orchestrator
 * turns it into a run error, not an empty input.
 *
 * <p>Builds its own plain {@link ObjectMapper} rather than injecting Spring
 * Boot's auto-configured one: parsing an arbitrary step output into a
 * {@link JsonNode} tree needs none of that bean's application-wide
 * customization (naming strategy, JSR-310 modules, ...), and depending on it
 * would tie this module to {@code :boot:app}'s own Jackson auto-configuration
 * actually firing (it does not always — Boot 4.1 does not guarantee a
 * consumer classpath alone registers one).
 */
@Component
class InputResolver {

    private static final Pattern REF_PATTERN = Pattern.compile("^\\{step:(\\d+)\\.(\\w+)}$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param stepOrder     1-based position of the step being resolved
     * @param inputRef      the step definition's raw {@code input_ref}, or
     *                      {@code null}/blank for the default (previous
     *                      output)
     * @param runInput      the owning run's own input (used for step 1 only)
     * @param priorOutputs  step_order -> output, for every step already
     *                      completed earlier in this same run
     */
    String resolve(int stepOrder, String inputRef, String runInput, Map<Integer, String> priorOutputs) {
        if (stepOrder == 1) {
            return runInput;
        }
        if (inputRef != null && !inputRef.isBlank()) {
            return resolveRef(inputRef, priorOutputs);
        }
        int previousOrder = stepOrder - 1;
        // priorOutputs.get(...) == null is NOT "absent" here: the orchestrator
        // records every completed step's output (including a genuinely
        // null/empty one, e.g. an "echo" step given the run's own null
        // input), so a null VALUE for an already-completed step is real data
        // to default through, not a resolution failure. containsKey is the
        // only check that distinguishes that from the actual error case —
        // step (stepOrder - 1) never completed at all (absent key).
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
