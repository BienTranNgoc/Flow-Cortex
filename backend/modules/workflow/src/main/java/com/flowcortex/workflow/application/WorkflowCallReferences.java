package com.flowcortex.workflow.application;

import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The workflow ids a definition's {@code workflow.call} steps name are ids of the environment the definition came
 * from, so a copy carries them only as far as the new owner can still see the workflow they point at.
 */
final class WorkflowCallReferences {

    private WorkflowCallReferences() {
    }

    /**
     * The step orders, ascending, of the {@code workflow.call} steps whose named workflow {@code viewerId} cannot read
     * (absent, deleted or not shared with them). A call step whose config is not a valid call config names nothing
     * here: the definition rules refuse it on their own.
     */
    static List<Integer> unresolved(List<StepDefinition> steps, UUID viewerId, WorkflowRepository workflows) {
        Map<Integer, UUID> named = new LinkedHashMap<>();
        steps.stream()
                .filter(step -> WorkflowCallStepExecutor.TYPE.equals(step.type()))
                .forEach(step -> {
                    WorkflowCallConfig.Result parsed = WorkflowCallConfig.parse(step.config());
                    if (parsed.config() != null) {
                        named.put(step.stepOrder(), parsed.config().workflowId());
                    }
                });
        if (named.isEmpty()) {
            return List.of();
        }
        Set<UUID> readable = Set.copyOf(workflows.findVisibleIds(Set.copyOf(named.values()), viewerId));
        return named.entrySet().stream()
                .filter(call -> !readable.contains(call.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
