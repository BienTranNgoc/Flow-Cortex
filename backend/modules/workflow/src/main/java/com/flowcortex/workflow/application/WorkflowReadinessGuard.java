package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.RunStartGuard;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Refuses a run of a workflow with no steps, or with a step that cannot work as configured: a configuration that is
 * not a JSON object, an empty {@code profile}, or an input reference that is malformed or does not
 * name an earlier step (by order or by unique name). Every failing step is reported as {@code errors["step<order>"] = reason}.
 */
@Component
@Order(1)
class WorkflowReadinessGuard implements RunStartGuard {

    static final String CODE = "WORKFLOW_INCOMPLETE";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void check(UUID ownerId, UUID workflowId, List<StepDef> steps, StartContext context) {
        Map<Integer, LocalizedMessage> faults = faults(steps);
        if (faults.isEmpty()) {
            return;
        }
        if (steps.isEmpty()) {
            throw new UnprocessableEntityException(faults.get(0), CODE);
        }
        Map<String, LocalizedMessage> failures = new LinkedHashMap<>();
        faults.forEach((order, reason) -> failures.put("step" + order, reason));
        int firstFailing = faults.keySet().iterator().next();
        throw new UnprocessableEntityException(new LocalizedMessage("workflow.incomplete", firstFailing), CODE,
                failures);
    }

    /**
     * Every reason this workflow cannot start, keyed by step order in step order; a workflow without steps is one
     * fault under key {@code 0}. Empty when the workflow can start.
     */
    Map<Integer, LocalizedMessage> faults(List<StepDef> steps) {
        if (steps.isEmpty()) {
            return Map.of(0, new LocalizedMessage("workflow.incomplete.noSteps"));
        }
        List<StepReferences.Step> referenceSteps = steps.stream()
                .map(step -> new StepReferences.Step(UUID.randomUUID(), step.order(), step.name(), step.inputRef()))
                .toList();
        Map<UUID, StepReferences.Problem> problems = StepReferences.analyze(referenceSteps);
        Map<Integer, String> typeByOrder = new HashMap<>();
        steps.forEach(step -> typeByOrder.put(step.order(), step.type()));
        Map<Integer, LocalizedMessage> faults = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            StepDef step = steps.get(i);
            StepReferences.Problem problem = problems.get(referenceSteps.get(i).id());
            configReason(step, typeByOrder).or(() -> referenceReason(step, problem))
                    .ifPresent(reason -> faults.put(step.order(), reason));
        }
        return faults;
    }

    /**
     * What is wrong with the config of {@code step}. A source step is found by its order in {@code typeByOrder},
     * never by list position: a run retry checks only the steps it re-executes, and an entry whose source step is
     * not among them reads a reused step, so it is not checked.
     */
    private Optional<LocalizedMessage> configReason(StepDef step, Map<Integer, String> typeByOrder) {
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
            return StepFileOutputs.firstUndeclared(step.config(), order -> {
                        String sourceType = typeByOrder.get(order);
                        return sourceType == null ? null : StepFileOutputs.declared(sourceType).orElse(null);
                    })
                    .map(undeclared -> new LocalizedMessage("workflow.incomplete.outputNotDeclared",
                            undeclared.file(), undeclared.sourceOrder()));
        }
        return Optional.empty();
    }

    private static Optional<LocalizedMessage> referenceReason(StepDef step, StepReferences.Problem problem) {
        if (problem == null) {
            return Optional.empty();
        }
        boolean byName = StepReferences.parse(step.inputRef())
                .filter(reference -> reference.head() instanceof StepReferences.Name)
                .isPresent();
        String detail = problem.detail();
        return Optional.of(switch (problem.kind()) {
            case MALFORMED -> new LocalizedMessage("workflow.incomplete.referenceMalformed");
            case AMBIGUOUS_NAME -> new LocalizedMessage("workflow.incomplete.ambiguousStepName", detail);
            case UNKNOWN_STEP -> byName
                    ? new LocalizedMessage("workflow.incomplete.unknownStepName", detail)
                    : new LocalizedMessage("workflow.incomplete.notEarlierStep", detail);
            case NOT_EARLIER -> byName
                    ? new LocalizedMessage("workflow.incomplete.notEarlierNamedStep", detail)
                    : new LocalizedMessage("workflow.incomplete.notEarlierStep", detail);
        });
    }
}
