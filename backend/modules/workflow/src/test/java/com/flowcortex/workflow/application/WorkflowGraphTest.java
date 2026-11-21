package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.WorkflowGraphView;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowGraphTest {

    private static final UUID WORKFLOW = UUID.randomUUID();

    private static WorkflowStep step(int order, String name, String type, String inputRef, String config) {
        return new WorkflowStep(UUID.randomUUID(), WORKFLOW, (short) order, name, type, inputRef, config);
    }

    private static WorkflowStep echo(int order, String name, String inputRef) {
        return step(order, name, "echo", inputRef, null);
    }

    private static WorkflowGraphView project(List<WorkflowStep> steps) {
        return WorkflowGraph.project(WORKFLOW, steps,
                (type, config) -> type.equals("remote")
                        ? (WorkflowGraphViewFixtures.declares(config)
                                ? StepExecutor.InputPolicy.STORED_WITH_OUTPUTS : StepExecutor.InputPolicy.STORED)
                        : StepExecutor.InputPolicy.RESOLVED,
                type -> type.equals("remote") ? "remote" : "inline");
    }

    private static List<String> edges(WorkflowGraphView view, List<WorkflowStep> steps) {
        List<String> out = new ArrayList<>();
        for (WorkflowGraphView.Edge edge : view.edges()) {
            out.add(orderOf(steps, edge.fromStepId()) + ">" + orderOf(steps, edge.toStepId()) + ":" + edge.kind());
        }
        return out;
    }

    private static int orderOf(List<WorkflowStep> steps, UUID id) {
        return steps.stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow().getStepOrder();
    }

    @Test
    void aLinearChainHasPreviousEdgesAndStepOneReadsTheRunInput() {
        List<WorkflowStep> steps = List.of(echo(1, "a", null), echo(2, "b", null), echo(3, "c", null));

        WorkflowGraphView view = project(steps);

        assertThat(view.nodes()).extracting(WorkflowGraphView.Node::readsRunInput).containsExactly(true, false, false);
        assertThat(edges(view, steps)).containsExactly("1>2:previous", "2>3:previous");
        assertThat(view.problems()).isEmpty();
    }

    @Test
    void aNamedReferenceDrawsAReferenceEdgeAndNoPreviousEdge() {
        List<WorkflowStep> steps = List.of(echo(1, "draft", null), echo(2, "voice", null),
                echo(3, "mix", "{step:draft.title}"));

        assertThat(edges(project(steps), steps)).containsExactly("1>2:previous", "1>3:reference");
    }

    @Test
    void aStoredWithOutputsStepHasOneEdgePerDistinctDeclaredSource() {
        String config = "{\"outputInputs\":[{\"stepOrder\":3,\"outputName\":\"a\",\"inputKey\":\"x\"},"
                + "{\"stepOrder\":4,\"outputName\":\"b\",\"inputKey\":\"y\"},"
                + "{\"stepOrder\":3,\"outputName\":\"c\",\"inputKey\":\"z\"},{\"stepOrder\":9}]}";
        List<WorkflowStep> steps = List.of(step(1, "up", "remote", null, null), echo(2, "e", null),
                echo(3, "f", null), echo(4, "g", null), step(5, "r", "remote", null, config));

        WorkflowGraphView view = project(steps);

        assertThat(edges(view, steps)).contains("2>3:previous", "3>4:previous")
                .filteredOn(e -> e.endsWith("outputInput")).containsExactly("3>5:outputInput", "4>5:outputInput");
        assertThat(view.nodes().get(4).readsRunInput()).isTrue();
        assertThat(edges(view, steps)).noneMatch(e -> e.startsWith("4>5:previous"));
    }

    @Test
    void aStoredStepReadsTheRunInputWithNoEdge() {
        List<WorkflowStep> steps = List.of(echo(1, "a", null), step(2, "r", "remote", null, null));

        WorkflowGraphView view = project(steps);

        assertThat(view.nodes().get(1).readsRunInput()).isTrue();
        assertThat(view.edges()).isEmpty();
    }

    @Test
    void aBrokenReferenceIsAProblemWithNoEdge() {
        List<WorkflowStep> steps = List.of(echo(1, "a", "{step:script}"), echo(2, "b", "{step:5.output}"),
                echo(3, "c", "{step:}"));

        WorkflowGraphView view = project(steps);

        assertThat(view.problems()).extracting(WorkflowGraphView.Problem::stepOrder, WorkflowGraphView.Problem::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "UNKNOWN_STEP"),
                        org.assertj.core.groups.Tuple.tuple(2, "UNKNOWN_STEP"),
                        org.assertj.core.groups.Tuple.tuple(3, "MALFORMED"));
        assertThat(view.edges()).isEmpty();
    }

    @Test
    void theProjectionCarriesNoConfigInputRefOrPath() {
        List<String> names = new ArrayList<>();
        for (Class<?> type : List.of(WorkflowGraphView.class, WorkflowGraphView.Node.class,
                WorkflowGraphView.Edge.class, WorkflowGraphView.Problem.class)) {
            for (var component : type.getRecordComponents()) {
                names.add(component.getName());
            }
        }

        assertThat(names).noneMatch(n -> n.toLowerCase().contains("config") || n.toLowerCase().contains("inputref")
                || n.toLowerCase().contains("path"));
    }

    private static final class WorkflowGraphViewFixtures {
        static boolean declares(String config) {
            return StoredWithOutputsResolver.declaresOutputInputs(config);
        }
    }
}
