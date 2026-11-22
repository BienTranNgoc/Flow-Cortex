package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Locks the one carry declaration against every hand-written list a step passes through. */
class WorkflowCarryTest {

    private static final Set<String> NOT_CARRIED = Set.of("id", "workflowId");
    private static final UUID SOURCE = UUID.randomUUID();

    private static WorkflowStep step(int order, String name, String type, String inputRef, String config) {
        return new WorkflowStep(UUID.randomUUID(), SOURCE, (short) order, name, type, inputRef, config);
    }

    private static Set<String> persistentStepFields() {
        return Arrays.stream(WorkflowStep.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.isAnnotationPresent(Column.class))
                .map(Field::getName)
                .filter(name -> !NOT_CARRIED.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyPersistentStepFieldExceptIdAndWorkflowIdIsCarried() {
        Set<String> missing = new TreeSet<>(persistentStepFields());
        missing.removeAll(WorkflowCarry.CARRIED_STEP_FIELDS);

        assertThat(missing)
                .as("WorkflowStep fields missing from WorkflowCarry.CARRIED_STEP_FIELDS: %s", missing)
                .isEmpty();
        assertThat(WorkflowCarry.CARRIED_STEP_FIELDS).containsExactlyInAnyOrderElementsOf(persistentStepFields());
    }

    @Test
    void theStepRecordHasExactlyTheCarriedFields() {
        Set<String> components = Arrays.stream(StepDefinition.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertThat(components).isEqualTo(WorkflowCarry.CARRIED_STEP_FIELDS);
    }

    @Test
    void theArtifactStepKeysAreExactlyTheCarriedFields() {
        StepDefinition full = new StepDefinition(1, "Name", "echo", "{\"a\":1}", "{step:1}");
        ObjectNode written = WorkflowArtifacts.write("Demo", List.of(full), Instant.parse("2026-11-11T10:00:00Z"));
        Set<String> writtenKeys = new TreeSet<>();
        written.get("steps").get(0).fieldNames().forEachRemaining(writtenKeys::add);

        assertThat(writtenKeys).isEqualTo(new TreeSet<>(WorkflowCarry.CARRIED_STEP_FIELDS));
        assertThat(WorkflowArtifacts.read(written.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WorkflowArtifacts.Parsed.class);
        for (String key : WorkflowCarry.CARRIED_STEP_FIELDS) {
            ObjectNode without = written.deepCopy();
            ((ObjectNode) without.get("steps").get(0)).remove(key);
            assertThat(WorkflowArtifacts.read(without.toString().getBytes(StandardCharsets.UTF_8)))
                    .as("a step without %s is refused", key)
                    .isInstanceOf(WorkflowArtifacts.Invalid.class);
        }
        ObjectNode extra = written.deepCopy();
        ((ObjectNode) extra.get("steps").get(0)).put("ownerId", "x");
        assertThat(WorkflowArtifacts.read(extra.toString().getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(WorkflowArtifacts.Invalid.class);
    }

    @Test
    void aVersionSnapshotRoundTripKeepsEveryCarriedField() throws Exception {
        Workflow workflow = new Workflow(SOURCE, UUID.randomUUID(), "Demo", true);
        WorkflowStep original = step(2, "Prompt", "translate", "{step:1.script}", "{\"lang\":\"vi\"}");

        JsonNode snapshot = WorkflowSnapshots.of(workflow, List.of(original));
        WorkflowStep restored = WorkflowSnapshots.stepsOf(snapshot, SOURCE).get(0);

        for (String field : WorkflowCarry.CARRIED_STEP_FIELDS) {
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Object expected = WorkflowStep.class.getMethod(getter).invoke(original);
            Object actual = WorkflowStep.class.getMethod(getter).invoke(restored);
            assertThat(actual).as("snapshot round trip keeps %s", field).isEqualTo(expected);
            assertThat(expected).as("the fixture populates %s", field).isNotNull();
        }
    }

    @Test
    void copiedStepsGetFreshIdsAndKeepOrderNameTypeConfigAndInputRef() {
        List<WorkflowStep> source = List.of(
                step(1, "Prompt", "translate", null, "{\"lang\":\"vi\"}"),
                step(2, null, "echo", "{step:1.script}", null));
        UUID target = UUID.randomUUID();

        List<WorkflowStep> copies = WorkflowCarry.copySteps(WorkflowCarry.of(source), target);

        assertThat(copies).hasSize(2);
        assertThat(copies).extracting(WorkflowStep::getId)
                .doesNotContainAnyElementsOf(source.stream().map(WorkflowStep::getId).toList())
                .doesNotHaveDuplicates();
        assertThat(copies).extracting(WorkflowStep::getStepOrder).containsExactly((short) 1, (short) 2);
        assertThat(copies).extracting(WorkflowStep::getName).containsExactly("Prompt", null);
        assertThat(copies).extracting(WorkflowStep::getType).containsExactly("translate", "echo");
        assertThat(copies).extracting(WorkflowStep::getConfig).containsExactly("{\"lang\":\"vi\"}", null);
        assertThat(copies).extracting(WorkflowStep::getInputRef).containsExactly(null, "{step:1.script}");
    }

    @Test
    void copiedStepsBelongToTheNewWorkflow() {
        UUID target = UUID.randomUUID();
        List<StepDefinition> definitions = new ArrayList<>();
        definitions.add(new StepDefinition(1, null, "echo", null, null));
        definitions.add(new StepDefinition(2, null, "echo", null, null));

        assertThat(WorkflowCarry.copySteps(definitions, target))
                .extracting(WorkflowStep::getWorkflowId).containsOnly(target);
    }
}
