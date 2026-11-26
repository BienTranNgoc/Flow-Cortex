package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.flowcortex.workflow.domain.WorkflowDataEntry;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one declaration of what a copied workflow step keeps: every persistent field of a step except its id and
 * workflow id. A fork and an import both write steps through {@link #copySteps}, and a test locks the declaration
 * against the entity, {@link StepDefinition}, the artifact's step keys and the version snapshot. The workflow's
 * data-store rows travel as {@link WorkflowDataStore.Seed}s ({@link #seedsOf}) and are written through
 * {@link WorkflowDataStore#seed}, so the store's own rules apply to every copy.
 */
final class WorkflowCarry {

    static final Set<String> CARRIED_STEP_FIELDS = Set.of("stepOrder", "name", "type", "config", "inputRef");

    /** Every persistent field of a data-store row except its id, workflow id, size and timestamps. */
    static final Set<String> CARRIED_ENTRY_FIELDS = Set.of("key", "dataType", "value");

    /** The data type {@code load_template} reads. */
    private static final String TEMPLATE_TYPE = "template";

    private WorkflowCarry() {
    }

    /** New steps with fresh ids, belonging to {@code newWorkflowId}, in the order given. */
    static List<WorkflowStep> copySteps(List<StepDefinition> steps, UUID newWorkflowId) {
        return steps.stream()
                .map(step -> new WorkflowStep(UUID.randomUUID(), newWorkflowId, (short) step.stepOrder(), step.name(),
                        step.type(), step.inputRef(), step.config()))
                .toList();
    }

    static List<StepDefinition> of(List<WorkflowStep> steps) {
        return steps.stream()
                .map(step -> new StepDefinition(step.getStepOrder(), step.getName(), step.getType(), step.getConfig(),
                        step.getInputRef()))
                .toList();
    }

    /**
     * The rows of a workflow a member may copy: the {@code template}-typed ones, which the owner opted in to
     * sharing with whoever runs the workflow. Any other row stays the owner's private data.
     */
    static List<WorkflowDataEntry> sharedWithRunners(List<WorkflowDataEntry> rows) {
        return rows.stream().filter(row -> TEMPLATE_TYPE.equals(row.getDataType())).toList();
    }

    /** The entries a store row hands on: the rest (ids, size, timestamps) is the new row's own. */
    static List<WorkflowDataStore.Seed> seedsOf(List<WorkflowDataEntry> rows) {
        return rows.stream()
                .map(row -> new WorkflowDataStore.Seed(row.getKey(), row.getDataType(), valueOf(row)))
                .toList();
    }

    private static JsonNode valueOf(WorkflowDataEntry row) {
        try {
            return DataStoreJson.mapper().readTree(row.getValue());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("data store entry " + row.getId() + " holds unreadable JSON", e);
        }
    }
}
