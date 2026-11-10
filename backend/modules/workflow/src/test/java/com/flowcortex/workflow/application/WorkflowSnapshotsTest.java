package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowSnapshotsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private final Workflow workflow = new Workflow(WORKFLOW_ID, UUID.randomUUID(), "Demo", true);

    private static WorkflowStep step(int order, String name, String type, String inputRef, String config) {
        return new WorkflowStep(UUID.randomUUID(), WORKFLOW_ID, (short) order, name, type, inputRef, config);
    }

    private static JsonNode parse(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void aSnapshotHoldsTheNameAndEveryStepFieldInOrderWithNullsWritten() {
        WorkflowStep second = step(2, null, "echo", null, null);
        WorkflowStep first = step(1, "Prompt", "translate", "{step:1}", "{\"lang\":\"vi\"}");

        JsonNode snapshot = WorkflowSnapshots.of(workflow, List.of(second, first));

        assertThat(snapshot.get("name").asText()).isEqualTo("Demo");
        JsonNode steps = snapshot.get("steps");
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).get("id").asText()).isEqualTo(first.getId().toString());
        assertThat(steps.get(0).get("stepOrder").isInt()).isTrue();
        assertThat(steps.get(0).get("config").get("lang").asText()).isEqualTo("vi");
        assertThat(steps.get(0).get("inputRef").asText()).isEqualTo("{step:1}");
        JsonNode bare = steps.get(1);
        assertThat(bare.has("name")).isTrue();
        assertThat(bare.get("name").isNull()).isTrue();
        assertThat(bare.get("config").isNull()).isTrue();
        assertThat(bare.get("inputRef").isNull()).isTrue();
    }

    @Test
    void twoSnapshotsDifferingOnlyInConfigKeyOrderAreTheSameDefinition() throws Exception {
        JsonNode a = parse("{\"name\":\"W\",\"steps\":[{\"id\":\"1\",\"config\":{\"a\":1,\"b\":2}}]}");
        JsonNode b = parse("{\"name\":\"W\",\"steps\":[{\"id\":\"1\",\"config\":{\"b\":2,\"a\":1}}]}");

        assertThat(WorkflowSnapshots.sameDefinition(a, b)).isTrue();
        assertThat(WorkflowSnapshots.sameDefinition(b, a)).isTrue();
    }

    @Test
    void aFreshSnapshotEqualsItsStoredAndReparsedForm() throws Exception {
        WorkflowStep only = step(1, "N", "echo", null, "{\"n\":3,\"x\":2.50}");

        JsonNode fresh = WorkflowSnapshots.of(workflow, List.of(only));
        JsonNode stored = parse(fresh.toString());

        assertThat(WorkflowSnapshots.sameDefinition(fresh, stored)).isTrue();
        assertThat(WorkflowSnapshots.sameDefinition(stored, fresh)).isTrue();
    }

    @Test
    void aChangedInputRefIsADifferentDefinition() {
        WorkflowStep before = step(1, "N", "echo", null, null);
        WorkflowStep after = new WorkflowStep(before.getId(), WORKFLOW_ID, (short) 1, "N", "echo", "{step:1}", null);

        JsonNode a = WorkflowSnapshots.of(workflow, List.of(before));
        JsonNode b = WorkflowSnapshots.of(workflow, List.of(after));

        assertThat(WorkflowSnapshots.sameDefinition(a, b)).isFalse();
        assertThat(WorkflowSnapshots.sameDefinition(b, a)).isFalse();
    }

    @Test
    void stepsOfRebuildsTransientStepsWithTheSnapshotIdsAndOrder() {
        WorkflowStep first = step(1, "Prompt", "translate", null, "{\"lang\":\"vi\"}");
        WorkflowStep second = step(2, null, "echo", "{step:1}", null);
        JsonNode snapshot = WorkflowSnapshots.of(workflow, List.of(first, second));
        UUID target = UUID.randomUUID();

        List<WorkflowStep> steps = WorkflowSnapshots.stepsOf(snapshot, target);

        assertThat(steps).extracting(WorkflowStep::getId).containsExactly(first.getId(), second.getId());
        assertThat(steps).extracting(WorkflowStep::getWorkflowId).containsOnly(target);
        assertThat(steps).extracting(WorkflowStep::getStepOrder).containsExactly((short) 1, (short) 2);
        assertThat(steps.get(0).getName()).isEqualTo("Prompt");
        assertThat(steps.get(0).getConfig()).isEqualTo("{\"lang\":\"vi\"}");
        assertThat(steps.get(1).getName()).isNull();
        assertThat(steps.get(1).getConfig()).isNull();
        assertThat(steps.get(1).getInputRef()).isEqualTo("{step:1}");
    }

    @Test
    void theSkeletonKeepsIdOrderNameAndTypeOnly() {
        WorkflowStep only = step(1, "Prompt", "translate", "{step:1}", "{\"secret\":\"owner prompt\"}");

        JsonNode skeleton = WorkflowSnapshots.skeleton(WorkflowSnapshots.of(workflow, List.of(only)));

        assertThat(skeleton.get("name").asText()).isEqualTo("Demo");
        JsonNode step = skeleton.get("steps").get(0);
        assertThat(step.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "stepOrder", "name", "type");
        assertThat(step.get("id").asText()).isEqualTo(only.getId().toString());
        assertThat(step.get("type").asText()).isEqualTo("translate");
    }
}
