package com.flowcortex.workflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * The definition a version holds. A {@code skeleton} snapshot carries only each step's id, order, name and type:
 * {@code config} and {@code inputRef} are null in it whatever the stored step holds.
 */
public record VersionSnapshotView(String name, boolean skeleton, List<Step> steps) {

    /** One step of the snapshot; {@code config} is the stored JSON object or null. */
    public record Step(UUID id, int stepOrder, String name, String type, JsonNode config, String inputRef) {
    }
}
