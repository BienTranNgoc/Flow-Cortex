package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.application.VersionDiff.Status;
import com.flowcortex.workflow.application.VersionDiff.StepChange;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionDiffTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A step as {id, order, name, type, config-json}; the type defaults to echo. */
    private static ObjectNode step(String id, int order, String name, String configJson) {
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("id", id);
            node.put("stepOrder", order);
            node.put("name", name);
            node.put("type", "echo");
            node.set("config", configJson == null ? JSON.nullNode() : JSON.readTree(configJson));
            node.putNull("inputRef");
            return node;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode snapshot(String name, ObjectNode... steps) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", name);
        ArrayNode array = node.putArray("steps");
        for (ObjectNode step : steps) {
            array.add(step);
        }
        return node;
    }

    private static StepChange only(VersionDiff diff) {
        assertThat(diff.steps()).hasSize(1);
        return diff.steps().get(0);
    }

    @Test
    void anEditedConfigIsModifiedWithBothValues() {
        JsonNode from = snapshot("W", step("a", 1, "A", "{\"p\":\"old\"}"));
        JsonNode to = snapshot("W", step("a", 1, "A", "{\"p\":\"new\"}"));

        StepChange change = only(VersionDiff.between(from, to));

        assertThat(change.status()).isEqualTo(Status.MODIFIED);
        assertThat(change.changedFields()).containsExactly("config");
        assertThat(change.before().get("config").get("p").asText()).isEqualTo("old");
        assertThat(change.after().get("config").get("p").asText()).isEqualTo("new");
    }

    @Test
    void aStepOnlyInTheTargetIsAdded() {
        JsonNode from = snapshot("W", step("a", 1, "A", null));
        JsonNode to = snapshot("W", step("a", 1, "A", null), step("b", 2, "B", null));

        StepChange change = only(VersionDiff.between(from, to));

        assertThat(change.stepId()).isEqualTo("b");
        assertThat(change.status()).isEqualTo(Status.ADDED);
        assertThat(change.fromOrder()).isNull();
        assertThat(change.toOrder()).isEqualTo(2);
        assertThat(change.before()).isNull();
        assertThat(change.after().get("name").asText()).isEqualTo("B");
    }

    @Test
    void aStepOnlyInTheSourceIsRemoved() {
        JsonNode from = snapshot("W", step("a", 1, "A", null), step("b", 2, "B", null));
        JsonNode to = snapshot("W", step("a", 1, "A", null));

        StepChange change = only(VersionDiff.between(from, to));

        assertThat(change.stepId()).isEqualTo("b");
        assertThat(change.status()).isEqualTo(Status.REMOVED);
        assertThat(change.fromOrder()).isEqualTo(2);
        assertThat(change.toOrder()).isNull();
        assertThat(change.after()).isNull();
        assertThat(change.before().get("name").asText()).isEqualTo("B");
    }

    @Test
    void swappedStepsWithEqualFieldsAreMoved() {
        JsonNode from = snapshot("W", step("a", 1, "A", null), step("b", 2, "B", null));
        JsonNode to = snapshot("W", step("b", 1, "B", null), step("a", 2, "A", null));

        StepChange change = only(VersionDiff.between(from, to));

        assertThat(change.status()).isEqualTo(Status.MOVED);
        assertThat(change.stepId()).isEqualTo("b");
        assertThat(change.fromOrder()).isEqualTo(2);
        assertThat(change.toOrder()).isEqualTo(1);
        assertThat(change.changedFields()).isEmpty();
    }

    @Test
    void aRenamedWorkflowIsReported() {
        JsonNode from = snapshot("Old", step("a", 1, "A", null));
        JsonNode to = snapshot("New", step("a", 1, "A", null));

        VersionDiff diff = VersionDiff.between(from, to);

        assertThat(diff.name().changed()).isTrue();
        assertThat(diff.name().before()).isEqualTo("Old");
        assertThat(diff.name().after()).isEqualTo("New");
        assertThat(diff.steps()).isEmpty();
    }

    @Test
    void identicalSnapshotsHaveNoDifference() {
        JsonNode from = snapshot("W", step("a", 1, "A", "{\"x\":2.5}"), step("b", 2, "B", null));
        JsonNode to = snapshot("W", step("a", 1, "A", "{\"x\":2.50}"), step("b", 2, "B", null));

        VersionDiff diff = VersionDiff.between(from, to);

        assertThat(diff.name().changed()).isFalse();
        assertThat(diff.steps()).isEmpty();
    }

    @Test
    void unnamedStepsAreMatchedByIdNotPosition() {
        JsonNode from = snapshot("W", step("a", 1, null, "{\"p\":1}"), step("b", 2, null, "{\"p\":2}"));
        JsonNode to = snapshot("W", step("a", 1, null, "{\"p\":1}"), step("b", 2, null, "{\"p\":3}"));

        StepChange change = only(VersionDiff.between(from, to));

        assertThat(change.stepId()).isEqualTo("b");
        assertThat(change.status()).isEqualTo(Status.MODIFIED);
    }

    @Test
    void deletingTheFirstStepMovesNothing() {
        JsonNode from = snapshot("W", step("a", 1, "A", null), step("b", 2, "B", null), step("c", 3, "C", null));
        JsonNode to = snapshot("W", step("b", 1, "B", null), step("c", 2, "C", null));

        StepChange change = only(VersionDiff.between(from, to));

        assertThat(change.stepId()).isEqualTo("a");
        assertThat(change.status()).isEqualTo(Status.REMOVED);
    }

    @Test
    void aStepMovedAfterTwoOthersIsTheOnlyMove() {
        JsonNode from = snapshot("W", step("a", 1, "A", null), step("b", 2, "B", null), step("c", 3, "C", null),
                step("d", 4, "D", null));
        JsonNode to = snapshot("W", step("b", 1, "B", null), step("c", 2, "C", null), step("a", 3, "A", null),
                step("d", 4, "D", null));

        StepChange change = only(VersionDiff.between(from, to));

        assertThat(change.stepId()).isEqualTo("a");
        assertThat(change.status()).isEqualTo(Status.MOVED);
    }

    @Test
    void aLargeSnapshotWithOneMoveIsDiffedQuickly() {
        int count = 20_000;
        ObjectNode[] before = new ObjectNode[count];
        ObjectNode[] after = new ObjectNode[count];
        for (int i = 0; i < count; i++) {
            before[i] = step("s" + i, i + 1, "S" + i, null);
        }
        // s0 moves to the end.
        for (int i = 1; i < count; i++) {
            after[i - 1] = step("s" + i, i, "S" + i, null);
        }
        after[count - 1] = step("s0", count, "S0", null);

        VersionDiff diff = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> VersionDiff.between(snapshot("W", before), snapshot("W", after)));

        StepChange change = only(diff);
        assertThat(change.stepId()).isEqualTo("s0");
        assertThat(change.status()).isEqualTo(Status.MOVED);
    }

    @Test
    void theSkeletonDiffKeepsChangedFieldNamesAndDropsValues() {
        JsonNode from = snapshot("W", step("a", 1, "A", "{\"p\":\"old\"}"), step("b", 2, "B", null));
        JsonNode to = snapshot("W", step("a", 1, "A", "{\"p\":\"new\"}"), step("c", 2, "C", "{\"s\":1}"));

        VersionDiff skeleton = VersionDiff.between(from, to).skeleton();

        List<StepChange> steps = skeleton.steps();
        assertThat(steps).extracting(StepChange::status)
                .containsExactly(Status.MODIFIED, Status.ADDED, Status.REMOVED);
        assertThat(steps.get(0).changedFields()).containsExactly("config");
        assertThat(steps).allSatisfy(change -> {
            assertThat(change.before()).isNull();
            assertThat(change.after()).isNull();
        });
    }
}
