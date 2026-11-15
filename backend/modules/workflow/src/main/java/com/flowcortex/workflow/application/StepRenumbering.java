package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

/**
 * Pure planner for deleting or reordering the steps of a workflow. Steps read each other by
 * position ({@code inputRef} {@code {step:K}} and {@code config.outputInputs[].stepOrder}), so
 * every renumber maps each such reference to the new order of the step it named. A reference whose
 * K names no current step is left as stored: it is neither a reader nor a forward reference. An
 * {@code inputRef} that names its step ({@code {step:NAME}}) reads the uniquely named step and is
 * forward-checked like a positional one, but is never rewritten: a name survives any renumber.
 */
final class StepRenumbering {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StepRenumbering() {
    }

    /** A stored step; {@code inputRef} and {@code config} are null when the step has none. */
    record Row(UUID id, int order, String type, String name, String inputRef, String config) {
    }

    /** A surviving step with its new order and what the renumber changed about it. */
    record Planned(Row row, int oldOrder, boolean inputRefChanged, boolean configChanged) {

        boolean orderChanged() {
            return row.order() != oldOrder;
        }

        boolean referencesChanged() {
            return inputRefChanged || configChanged;
        }
    }

    sealed interface Outcome permits Plan, Unchanged, Referenced, Duplicate, Stale, ForwardReference {
    }

    /** The surviving steps in their new sequence. */
    record Plan(List<Planned> rows) implements Outcome {
    }

    record Unchanged() implements Outcome {
    }

    /** The deleted step is read explicitly by the steps at {@code readers} (ascending, distinct). */
    record Referenced(int deletedOrder, List<Integer> readers) implements Outcome {
    }

    record Duplicate() implements Outcome {
    }

    record Stale() implements Outcome {
    }

    /** Zero-based indexes, in the new list, of steps whose explicit inputRef is not earlier than them. */
    record ForwardReference(List<Integer> newIndexes) implements Outcome {
    }

    static Outcome forDelete(List<Row> rows, UUID stepId) {
        List<Row> sorted = rows.stream().sorted(Comparator.comparingInt(Row::order)).toList();
        Row deleted = sorted.stream().filter(row -> row.id().equals(stepId)).findFirst().orElse(null);
        if (deleted == null) {
            return new Stale();
        }
        int n = sorted.size();
        Map<String, Integer> names = uniqueNames(sorted);
        int deletedOrder = deleted.order();
        TreeSet<Integer> readers = new TreeSet<>();
        for (Row row : sorted) {
            if (row == deleted) {
                continue;
            }
            boolean[] reads = {false};
            references(row, n, names, k -> {
                reads[0] |= k == deletedOrder;
                return k;
            });
            if (reads[0]) {
                readers.add(row.order());
            }
        }
        if (!readers.isEmpty()) {
            return new Referenced(deletedOrder, List.copyOf(readers));
        }
        IntUnaryOperator remap = k -> k > deletedOrder ? k - 1 : k;
        List<Planned> planned = new ArrayList<>();
        for (Row row : sorted) {
            if (row == deleted) {
                continue;
            }
            int newOrder = row.order() > deletedOrder ? row.order() - 1 : row.order();
            planned.add(rewritten(row, newOrder, n, remap));
        }
        return new Plan(planned);
    }

    static Outcome forOrder(List<Row> rows, List<UUID> stepIds) {
        Set<UUID> seen = new HashSet<>();
        for (UUID id : stepIds) {
            if (!seen.add(id)) {
                return new Duplicate();
            }
        }
        List<Row> sorted = rows.stream().sorted(Comparator.comparingInt(Row::order)).toList();
        Map<UUID, Row> byId = new HashMap<>();
        sorted.forEach(row -> byId.put(row.id(), row));
        if (stepIds.size() != sorted.size() || !byId.keySet().equals(seen)) {
            return new Stale();
        }
        boolean same = true;
        Map<Integer, Integer> newOrderOf = new HashMap<>();
        for (int i = 0; i < stepIds.size(); i++) {
            Row row = byId.get(stepIds.get(i));
            newOrderOf.put(row.order(), i + 1);
            same &= row.order() == i + 1;
        }
        if (same) {
            return new Unchanged();
        }
        int n = sorted.size();
        Map<String, Integer> names = uniqueNames(sorted);
        IntUnaryOperator remap = newOrderOf::get;
        List<Planned> planned = new ArrayList<>();
        List<Integer> forward = new ArrayList<>();
        for (int i = 0; i < stepIds.size(); i++) {
            Planned step = rewritten(byId.get(stepIds.get(i)), i + 1, n, remap);
            planned.add(step);
            Integer target = inputRefTarget(step.row().inputRef(), n, names);
            if (target != null && isNamed(step.row().inputRef())) {
                target = newOrderOf.get(target);
            }
            if (target != null && target >= step.row().order()) {
                forward.add(i);
            }
        }
        if (!forward.isEmpty()) {
            return new ForwardReference(List.copyOf(forward));
        }
        return new Plan(planned);
    }

    private static Planned rewritten(Row row, int newOrder, int n, IntUnaryOperator remap) {
        String inputRef = rewriteInputRef(row.inputRef(), n, remap);
        String config = rewriteConfig(row.config(), n, remap);
        return new Planned(new Row(row.id(), newOrder, row.type(), row.name(), inputRef, config), row.order(),
                !Objects.equals(inputRef, row.inputRef()), !Objects.equals(config, row.config()));
    }

    /** Visits every explicit reference of {@code row} that names a current step (1..n). */
    private static void references(Row row, int n, Map<String, Integer> names, IntUnaryOperator visit) {
        Integer target = inputRefTarget(row.inputRef(), n, names);
        if (target != null) {
            visit.applyAsInt(target);
        }
        rewriteConfig(row.config(), n, visit);
    }

    /**
     * The current order of the step an {@code inputRef} reads: the position when positional and within 1..n, the
     * order of the uniquely named step when by name, null when it reads no current step.
     */
    private static Integer inputRefTarget(String inputRef, int n, Map<String, Integer> names) {
        Optional<StepReferences.Reference> reference = StepReferences.parse(inputRef);
        if (reference.isEmpty()) {
            return null;
        }
        return switch (reference.get().head()) {
            case StepReferences.Order order -> order.value() >= 1 && order.value() <= n ? order.value() : null;
            case StepReferences.Name name -> names.get(name.value());
        };
    }

    private static boolean isNamed(String inputRef) {
        return StepReferences.parse(inputRef)
                .filter(reference -> reference.head() instanceof StepReferences.Name)
                .isPresent();
    }

    /** Name to order for the names exactly one row carries; an ambiguous name reads no step. */
    private static Map<String, Integer> uniqueNames(List<Row> rows) {
        Map<String, Integer> unique = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Row row : rows) {
            if (row.name() != null && unique.putIfAbsent(row.name(), row.order()) != null) {
                ambiguous.add(row.name());
            }
        }
        unique.keySet().removeAll(ambiguous);
        return unique;
    }

    private static String rewriteInputRef(String inputRef, int n, IntUnaryOperator remap) {
        Optional<StepReferences.Reference> parsed = StepReferences.parse(inputRef);
        if (parsed.isEmpty() || !(parsed.get().head() instanceof StepReferences.Order order)) {
            return inputRef;
        }
        int k = order.value();
        if (k < 1 || k > n) {
            return inputRef;
        }
        int mapped = remap.applyAsInt(k);
        if (mapped == k) {
            return inputRef;
        }
        return StepReferences.format(StepReferences.Order.of(mapped), parsed.get().path());
    }

    private static String rewriteConfig(String config, int n, IntUnaryOperator remap) {
        if (config == null || config.isBlank()) {
            return config;
        }
        JsonNode root;
        try {
            root = JSON.readTree(config);
        } catch (JsonProcessingException e) {
            return config;
        }
        if (root == null || !root.isObject()) {
            return config;
        }
        JsonNode mappings = root.get("outputInputs");
        if (mappings == null || !mappings.isArray()) {
            return config;
        }
        boolean changed = false;
        for (JsonNode mapping : mappings) {
            JsonNode stepOrder = mapping.isObject() ? mapping.get("stepOrder") : null;
            if (stepOrder == null || !stepOrder.isIntegralNumber() || !stepOrder.canConvertToInt()) {
                continue;
            }
            int k = stepOrder.intValue();
            if (k < 1 || k > n) {
                continue;
            }
            int mapped = remap.applyAsInt(k);
            if (mapped != k) {
                ((ObjectNode) mapping).put("stepOrder", mapped);
                changed = true;
            }
        }
        if (!changed) {
            return config;
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a rewritten step config", e);
        }
    }
}
