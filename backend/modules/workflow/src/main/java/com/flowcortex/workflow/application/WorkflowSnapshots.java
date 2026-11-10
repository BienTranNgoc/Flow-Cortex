package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Builds, compares and reads the JSON snapshot a workflow version stores:
 * {@code {name, steps:[{id, stepOrder, name, type, config, inputRef}]}}, every key always written (null, never
 * omitted), steps in order, {@code config} embedded as parsed JSON. The database backfill writes the same shape.
 */
public final class WorkflowSnapshots {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Two numeric nodes are equal when their decimal values compare equal, whatever their node class. */
    private static final Comparator<JsonNode> NUMERIC_TOLERANT = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return a.equals(b) ? 0 : 1;
    };

    private WorkflowSnapshots() {
    }

    public static ObjectNode of(Workflow workflow, List<WorkflowStep> steps) {
        ObjectNode snapshot = NODES.objectNode();
        snapshot.put("name", workflow.getName());
        ArrayNode stepNodes = snapshot.putArray("steps");
        steps.stream()
                .sorted(Comparator.comparingInt(WorkflowStep::getStepOrder))
                .forEach(step -> {
                    ObjectNode node = stepNodes.addObject();
                    node.put("id", step.getId().toString());
                    node.put("stepOrder", (int) step.getStepOrder());
                    node.put("name", step.getName());
                    node.put("type", step.getType());
                    node.set("config", parseConfig(step.getConfig()));
                    node.put("inputRef", step.getInputRef());
                });
        return snapshot;
    }

    /** Reads a stored snapshot; a row that is not JSON is corrupt data, never a caller mistake. */
    public static JsonNode parse(String snapshot) {
        try {
            return JSON.readTree(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("workflow version snapshot is not valid JSON", e);
        }
    }

    /** Parsed-tree equality: key order and numeric node class (short/int/long, 2.5/2.50) never matter. */
    public static boolean sameDefinition(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.equals(NUMERIC_TOLERANT, b);
    }

    /** Rebuilds transient steps (never persisted) carrying the snapshot's ids, orders and fields. */
    public static List<WorkflowStep> stepsOf(JsonNode snapshot, UUID workflowId) {
        List<WorkflowStep> steps = new ArrayList<>();
        for (JsonNode node : snapshot.path("steps")) {
            JsonNode config = node.path("config");
            steps.add(new WorkflowStep(
                    UUID.fromString(node.path("id").asText()),
                    workflowId,
                    (short) node.path("stepOrder").asInt(),
                    text(node.get("name")),
                    node.path("type").asText(),
                    text(node.get("inputRef")),
                    config.isMissingNode() || config.isNull() ? null : config.toString()));
        }
        return steps;
    }

    /** The shape a non-owner may read: the workflow name and each step's id, order, name and type. */
    public static ObjectNode skeleton(JsonNode snapshot) {
        ObjectNode out = NODES.objectNode();
        out.set("name", snapshot.path("name").deepCopy());
        ArrayNode steps = out.putArray("steps");
        for (JsonNode node : snapshot.path("steps")) {
            ObjectNode step = steps.addObject();
            for (String key : List.of("id", "stepOrder", "name", "type")) {
                step.set(key, node.has(key) ? node.get(key).deepCopy() : NODES.nullNode());
            }
        }
        return out;
    }

    private static JsonNode parseConfig(String config) {
        if (config == null) {
            return NODES.nullNode();
        }
        try {
            return JSON.readTree(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("workflow step config is not valid JSON", e);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
