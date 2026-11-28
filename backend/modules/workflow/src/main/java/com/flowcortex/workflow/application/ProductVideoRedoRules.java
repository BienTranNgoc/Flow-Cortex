package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

/** Pure dependency-closure and eligibility rules for partial Product Video scene/lip-sync redos. */
final class ProductVideoRedoRules {

    private static final Set<String> REDOABLE_TYPES = Set.of("generate_video", "lipsync_video");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProductVideoRedoRules() {
    }

    record Plan(Set<Integer> rerunOrders, List<RunStep> reusedSteps, List<RunStep> rerunSteps) {
    }

    static Plan resolve(UUID workflowId, RunStatus runStatus, List<RunStep> orderedSteps,
            List<Integer> requestedOrders) {
        if (!ProductVideoWorkflow.isProductVideo(workflowId)) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.redoNotEligible"));
        }
        if (runStatus != RunStatus.COMPLETED && runStatus != RunStatus.ERROR) {
            throw new ConflictException(new LocalizedMessage("workflow.productVideo.redoNotEligible"));
        }
        if (requestedOrders == null || requestedOrders.isEmpty()) {
            throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                    new LocalizedMessage("workflow.productVideo.redoStepInvalid"));
        }
        Map<Integer, RunStep> byOrder = orderedSteps.stream()
                .collect(Collectors.toMap(s -> (int) s.getStepOrder(), Function.identity()));

        Set<Integer> rerunOrders = new LinkedHashSet<>();
        for (Integer order : requestedOrders) {
            RunStep step = order == null ? null : byOrder.get(order);
            if (step == null || !REDOABLE_TYPES.contains(step.getType())) {
                throw new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                        new LocalizedMessage("workflow.productVideo.redoStepInvalid"));
            }
            rerunOrders.add(order);
        }

        // Expand downstream dependents (e.g. lipsync_video reading Scene 1, and render_video).
        boolean changed = true;
        while (changed) {
            changed = false;
            for (RunStep step : orderedSteps) {
                int order = step.getStepOrder();
                if (rerunOrders.contains(order)) {
                    continue;
                }
                if ("render_video".equals(step.getType()) || dependsOnAny(step.getConfig(), rerunOrders)) {
                    rerunOrders.add(order);
                    changed = true;
                }
            }
        }

        List<RunStep> reusedSteps = orderedSteps.stream()
                .filter(s -> !rerunOrders.contains((int) s.getStepOrder()))
                .toList();
        boolean allReusedCompleted = reusedSteps.stream()
                .allMatch(s -> s.getStatus() == RunStepStatus.COMPLETED);
        if (!allReusedCompleted) {
            throw new ConflictException(new LocalizedMessage("workflow.productVideo.redoNotEligible"));
        }
        List<RunStep> rerunSteps = orderedSteps.stream()
                .filter(s -> rerunOrders.contains((int) s.getStepOrder()))
                .toList();
        return new Plan(Set.copyOf(rerunOrders), reusedSteps, rerunSteps);
    }

    private static boolean dependsOnAny(String rawConfig, Set<Integer> orders) {
        if (rawConfig == null || rawConfig.isBlank()) {
            return false;
        }
        try {
            JsonNode outputInputs = JSON.readTree(rawConfig).get("outputInputs");
            if (outputInputs == null || !outputInputs.isArray()) {
                return false;
            }
            for (JsonNode entry : outputInputs) {
                JsonNode stepOrder = entry.get("stepOrder");
                if (stepOrder != null && stepOrder.isInt() && orders.contains(stepOrder.intValue())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException e) {
            return false;
        }
    }
}
