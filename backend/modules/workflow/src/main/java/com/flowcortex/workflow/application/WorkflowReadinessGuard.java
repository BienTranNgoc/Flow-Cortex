package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
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
        Map<String, LocalizedMessage> failures = new LinkedHashMap<>();
        Integer firstFailing = null;
        for (StepDef step : steps) {
            Optional<LocalizedMessage> reason = reason(step);
            if (reason.isPresent()) {
                failures.put("step" + step.order(), reason.get());
                if (firstFailing == null) {
                    firstFailing = step.order();
                }
            }
        }
        if (firstFailing != null) {
            throw new UnprocessableEntityException(new LocalizedMessage("workflow.incomplete", firstFailing), CODE,
                    failures);
        }
    }

    private Optional<LocalizedMessage> reason(StepDef step) {
        if (step.config() != null && !step.config().isBlank()) {
            JsonNode config;
            try {
                config = objectMapper.readTree(step.config());
            } catch (JsonProcessingException e) {
                return Optional.of(new LocalizedMessage("workflow.incomplete.configNotObject"));
            }
            if (!config.isObject()) {
                return Optional.of(new LocalizedMessage("workflow.incomplete.configNotObject"));
            }
            JsonNode profile = config.get("profile");
            if (profile != null && (!profile.isTextual() || profile.asText().isBlank())) {
                return Optional.of(new LocalizedMessage("workflow.incomplete.profileEmpty"));
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
                    return Optional.of(new LocalizedMessage("workflow.incomplete.notEarlierStep", named));
                }
            }
        }
        return Optional.empty();
    }
}
