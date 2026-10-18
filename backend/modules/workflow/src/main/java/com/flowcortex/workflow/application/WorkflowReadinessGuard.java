package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.RunStartGuard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Refuses a run of a workflow with a step that cannot work as configured: a configuration that is
 * not a JSON object, an empty {@code profile}, or an input reference to a step that does not run
 * before it. Every failing step is reported as {@code errors["step<order>"] = reason}.
 */
@Component
@Order(1)
class WorkflowReadinessGuard implements RunStartGuard {

    static final String CODE = "WORKFLOW_INCOMPLETE";
    private static final Pattern STEP_REFERENCE = Pattern.compile("^\\{step:(\\d+)\\.(\\w+)}$");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void check(UUID ownerId, UUID workflowId, List<StepDef> steps, StartContext context) {
        Map<String, String> failures = new LinkedHashMap<>();
        Integer firstFailing = null;
        for (StepDef step : steps) {
            Optional<String> reason = reason(step);
            if (reason.isPresent()) {
                failures.put("step" + step.order(), reason.get());
                if (firstFailing == null) {
                    firstFailing = step.order();
                }
            }
        }
        if (firstFailing != null) {
            throw new UnprocessableEntityException(
                    "This workflow can't run yet: step " + firstFailing + " is not fully configured.", CODE, failures);
        }
    }

    private Optional<String> reason(StepDef step) {
        if (step.config() != null && !step.config().isBlank()) {
            JsonNode config;
            try {
                config = objectMapper.readTree(step.config());
            } catch (JsonProcessingException e) {
                return Optional.of("Configuration is not a JSON object");
            }
            if (!config.isObject()) {
                return Optional.of("Configuration is not a JSON object");
            }
            JsonNode profile = config.get("profile");
            if (profile != null && (!profile.isTextual() || profile.asText().isBlank())) {
                return Optional.of("Profile is empty");
            }
        }
        if (step.inputRef() != null) {
            Matcher reference = STEP_REFERENCE.matcher(step.inputRef());
            if (reference.matches()) {
                String digits = reference.group(1);
                // More digits than any step order can have: parsing would overflow, and it is never earlier.
                long referenced = digits.length() > 9 ? Long.MAX_VALUE : Long.parseLong(digits);
                if (referenced < 1 || referenced >= step.order()) {
                    String named = referenced == Long.MAX_VALUE ? digits : Long.toString(referenced);
                    return Optional.of("Reads from step " + named + ", which is not an earlier step");
                }
            }
        }
        return Optional.empty();
    }
}
