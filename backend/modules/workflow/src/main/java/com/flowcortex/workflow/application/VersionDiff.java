package com.flowcortex.workflow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The difference between two workflow snapshots. Steps match by id; a common step with changed fields is
 * {@code MODIFIED}, one with equal fields is {@code MOVED} only when it falls outside the longest common
 * subsequence of the two sides' common step ids, so deleting a step never reports the steps after it as moved.
 */
public record VersionDiff(NameChange name, List<StepChange> steps) {

    /** The content fields of a step, in the order they are reported. */
    static final List<String> FIELDS = List.of("name", "type", "config", "inputRef");

    public enum Status { ADDED, REMOVED, MODIFIED, MOVED }

    public record NameChange(boolean changed, String before, String after) {
    }

    /**
     * One step's change. {@code before}/{@code after} map each reported field to its value (null for a skeleton
     * diff, and {@code before} is null for an added step, {@code after} for a removed one); {@code fromOrder} is
     * null for an added step and {@code toOrder} for a removed one.
     */
    public record StepChange(String stepId, Status status, Integer fromOrder, Integer toOrder, String name,
            String type, List<String> changedFields, Map<String, JsonNode> before, Map<String, JsonNode> after) {
    }

    /** Compares {@code from} to {@code to}: added and changed steps in target order, then removed ones. */
    public static VersionDiff between(JsonNode from, JsonNode to) {
        Map<String, JsonNode> source = stepsById(from);
        Map<String, JsonNode> target = stepsById(to);
        Set<String> moved = movedIds(source, target);

        List<StepChange> changes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : target.entrySet()) {
            String id = entry.getKey();
            JsonNode after = entry.getValue();
            JsonNode before = source.get(id);
            if (before == null) {
                changes.add(new StepChange(id, Status.ADDED, null, order(after), text(after, "name"),
                        text(after, "type"), List.of(), null, fields(after, FIELDS)));
                continue;
            }
            List<String> changed = FIELDS.stream()
                    .filter(field -> !WorkflowSnapshots.sameDefinition(value(before, field), value(after, field)))
                    .toList();
            if (!changed.isEmpty()) {
                changes.add(new StepChange(id, Status.MODIFIED, order(before), order(after), text(after, "name"),
                        text(after, "type"), changed, fields(before, changed), fields(after, changed)));
            } else if (moved.contains(id)) {
                changes.add(new StepChange(id, Status.MOVED, order(before), order(after), text(after, "name"),
                        text(after, "type"), List.of(), null, null));
            }
        }
        for (Map.Entry<String, JsonNode> entry : source.entrySet()) {
            if (!target.containsKey(entry.getKey())) {
                JsonNode before = entry.getValue();
                changes.add(new StepChange(entry.getKey(), Status.REMOVED, order(before), null, text(before, "name"),
                        text(before, "type"), List.of(), fields(before, FIELDS), null));
            }
        }

        String nameBefore = text(from, "name");
        String nameAfter = text(to, "name");
        boolean renamed = nameBefore == null ? nameAfter != null : !nameBefore.equals(nameAfter);
        return new VersionDiff(new NameChange(renamed, nameBefore, nameAfter), List.copyOf(changes));
    }

    /** The same diff with every field value dropped; changed field names and the shape of the change stay. */
    public VersionDiff skeleton() {
        List<StepChange> stripped = steps.stream()
                .map(step -> new StepChange(step.stepId(), step.status(), step.fromOrder(), step.toOrder(),
                        step.name(), step.type(), step.changedFields(), null, null))
                .toList();
        return new VersionDiff(name, stripped);
    }

    private static Map<String, JsonNode> stepsById(JsonNode snapshot) {
        Map<String, JsonNode> steps = new LinkedHashMap<>();
        for (JsonNode step : snapshot.path("steps")) {
            steps.put(step.path("id").asText(), step);
        }
        return steps;
    }

    /** Common step ids outside the LCS of the two sides' common ids; a tie keeps the earlier source steps. */
    private static Set<String> movedIds(Map<String, JsonNode> source, Map<String, JsonNode> target) {
        List<String> from = source.keySet().stream().filter(target::containsKey).toList();
        List<String> to = target.keySet().stream().filter(source::containsKey).toList();
        // Step ids are unique per side, so the common ids form a permutation and their LCS is the longest
        // increasing run of target positions in source order. The scan runs from the end of the source with
        // negated positions so each overwrite of a tail favours an earlier source step.
        int n = from.size();
        Map<String, Integer> targetPosition = new HashMap<>();
        for (int j = 0; j < to.size(); j++) {
            targetPosition.put(to.get(j), j);
        }
        int[] tails = new int[n];
        int[] previous = new int[n];
        int length = 0;
        for (int i = n - 1; i >= 0; i--) {
            int key = -targetPosition.get(from.get(i));
            int low = 0;
            int high = length;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (-targetPosition.get(from.get(tails[mid])) < key) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            previous[i] = low > 0 ? tails[low - 1] : -1;
            tails[low] = i;
            if (low == length) {
                length++;
            }
        }
        Set<String> moved = new LinkedHashSet<>(from);
        for (int i = length > 0 ? tails[length - 1] : -1; i >= 0; i = previous[i]) {
            moved.remove(from.get(i));
        }
        return moved;
    }

    private static Map<String, JsonNode> fields(JsonNode step, List<String> names) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (String name : names) {
            values.put(name, value(step, name));
        }
        return values;
    }

    private static JsonNode value(JsonNode step, String field) {
        JsonNode value = step.get(field);
        return value == null ? NullNode.getInstance() : value;
    }

    private static Integer order(JsonNode step) {
        return step.path("stepOrder").asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
