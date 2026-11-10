package com.flowcortex.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * The difference between two sides. For a skeleton reader {@code before} and {@code after} of every step are null
 * and only the names of the changed fields remain.
 */
public record VersionDiffView(DiffSide source, DiffSide target, boolean skeleton, NameChange name,
        List<StepChange> steps) {

    /** Whether the workflow name differs, with both values. */
    public record NameChange(boolean changed, String before, String after) {
    }

    /**
     * One step's change; {@code status} is {@code added}, {@code removed}, {@code modified} or {@code moved}.
     * {@code fromOrder} is null for an added step and {@code toOrder} for a removed one.
     */
    public record StepChange(String stepId, String status, Integer fromOrder, Integer toOrder, String name,
            String type, List<String> changedFields, Map<String, JsonNode> before, Map<String, JsonNode> after) {
    }
}
