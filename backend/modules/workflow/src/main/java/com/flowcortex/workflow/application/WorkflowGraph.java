package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.WorkflowGraphView;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Projects workflow steps into nodes, edges and reference problems from the engine's own input policy. */
final class WorkflowGraph {

    private static final String PREVIOUS = "previous";
    private static final String REFERENCE = "reference";
    private static final String OUTPUT_INPUT = "outputInput";

    private WorkflowGraph() {
    }

    /** {@code steps} in step order; {@code policyOf} takes (type, config), {@code modeOf} takes the type. */
    static WorkflowGraphView project(UUID workflowId, List<WorkflowStep> steps,
            BiFunction<String, String, StepExecutor.InputPolicy> policyOf, Function<String, String> modeOf) {
        List<StepReferences.Step> refSteps = steps.stream()
                .map(step -> new StepReferences.Step(step.getId(), step.getStepOrder(), step.getName(),
                        step.getInputRef()))
                .toList();
        Map<UUID, StepReferences.Problem> broken = StepReferences.analyze(refSteps);
        Map<Integer, WorkflowStep> byOrder = new HashMap<>();
        steps.forEach(step -> byOrder.put((int) step.getStepOrder(), step));

        List<WorkflowGraphView.Node> nodes = new ArrayList<>();
        List<WorkflowGraphView.Edge> edges = new ArrayList<>();
        List<WorkflowGraphView.Problem> problems = new ArrayList<>();
        WorkflowStep before = null;
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            StepExecutor.InputPolicy policy = policyOf.apply(step.getType(), step.getConfig());
            boolean first = before == null;
            boolean readsRunInput = first || policy != StepExecutor.InputPolicy.RESOLVED;
            nodes.add(new WorkflowGraphView.Node(step.getId(), step.getStepOrder(), step.getName(), step.getType(),
                    modeOf.apply(step.getType()), readsRunInput));
            StepReferences.Problem problem = broken.get(step.getId());
            if (problem != null) {
                problems.add(new WorkflowGraphView.Problem(step.getId(), step.getStepOrder(), problem.kind().name()));
            }
            switch (policy) {
                case RESOLVED -> {
                    if (!first && problem == null) {
                        resolvedEdge(step, before, refSteps.get(i), refSteps, byOrder).ifPresent(edges::add);
                    }
                }
                case STORED -> { }
                case STORED_WITH_OUTPUTS -> edges.addAll(outputInputEdges(step, byOrder));
                default -> throw new IllegalStateException("Unhandled input policy " + policy);
            }
            before = step;
        }
        return new WorkflowGraphView(workflowId, nodes, edges, problems);
    }

    private static java.util.Optional<WorkflowGraphView.Edge> resolvedEdge(WorkflowStep step, WorkflowStep before,
            StepReferences.Step refStep, List<StepReferences.Step> refSteps, Map<Integer, WorkflowStep> byOrder) {
        if (step.getInputRef() == null || step.getInputRef().isBlank()) {
            return java.util.Optional.of(new WorkflowGraphView.Edge(before.getId(), step.getId(), PREVIOUS));
        }
        OptionalInt target = StepReferences.resolve(refStep, refSteps);
        WorkflowStep source = target.isPresent() ? byOrder.get(target.getAsInt()) : null;
        return java.util.Optional.ofNullable(source)
                .map(from -> new WorkflowGraphView.Edge(from.getId(), step.getId(), REFERENCE));
    }

    private static List<WorkflowGraphView.Edge> outputInputEdges(WorkflowStep step,
            Map<Integer, WorkflowStep> byOrder) {
        List<WorkflowGraphView.Edge> edges = new ArrayList<>();
        for (int order : new LinkedHashSet<>(StoredWithOutputsResolver.declaredSources(step.getConfig()))) {
            WorkflowStep source = byOrder.get(order);
            if (source != null && order < step.getStepOrder()) {
                edges.add(new WorkflowGraphView.Edge(source.getId(), step.getId(), OUTPUT_INPUT));
            }
        }
        return edges;
    }
}
