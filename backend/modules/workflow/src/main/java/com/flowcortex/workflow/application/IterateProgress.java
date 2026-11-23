package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an iterate step does next, as a pure function of its children: the ordered decision table,
 * the gathered output and its regathering after a child is regenerated, and the step's deadline.
 */
final class IterateProgress {

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String INSUFFICIENT_CREDITS = "INSUFFICIENT_CREDITS";
    private static final String INSUFFICIENT_CREDITS_MESSAGE = "Not enough credits to run this step";
    private static final Pattern ITEM_FILE = Pattern.compile("^item-(\\d{1,5})-");

    /** One item as the decision reads it; {@code outputs} is the child's stored-outputs JSON array. */
    record ChildState(UUID id, int itemIndex, RunStepStatus status, String output, String outputs, String errorCode,
            String errorMessage) {

        static ChildState of(RunStepRepository.ChildRow row) {
            return new ChildState(row.getId(), row.getItemIndex(), row.getStatus(), row.getOutput(),
                    row.getOutputs(), row.getErrorCode(), row.getErrorMessage());
        }
    }

    sealed interface Decision permits Dispatch, Complete, Fail, Wait {
    }

    /** Start these pending children now, in this order. */
    record Dispatch(List<UUID> childIds) implements Decision {
    }

    /** Every child is terminal: the step completes with this output and stored outputs ({@code null} when none). */
    record Complete(String output, String outputs) implements Decision {
    }

    record Fail(String code, String message) implements Decision {
    }

    /** Nothing to start: a child still running will advance the run again. */
    record Wait() implements Decision {
    }

    private IterateProgress() {
    }

    /**
     * The first row that applies: an unpaid child fails the step whatever {@code onItemError} says; with
     * {@code fail} the first failed item fails it; all children terminal completes it; free slots start
     * the next pending children in item order; otherwise wait.
     */
    static Decision decide(IterateStepConfig config, List<ChildState> children, int dropped,
            int effectiveConcurrency) {
        if (effectiveConcurrency < 1) {
            throw new IllegalArgumentException("effectiveConcurrency must be at least 1");
        }
        List<ChildState> ordered = inItemOrder(children);
        for (ChildState child : ordered) {
            if (child.status() == RunStepStatus.ERROR && INSUFFICIENT_CREDITS.equals(child.errorCode())) {
                return new Fail(INSUFFICIENT_CREDITS, INSUFFICIENT_CREDITS_MESSAGE);
            }
        }
        if (config.failFast()) {
            for (ChildState child : ordered) {
                if (child.status() == RunStepStatus.ERROR) {
                    return new Fail("iterate_item_failed", "Item " + child.itemIndex() + " failed: " + reason(child));
                }
            }
        }
        if (ordered.stream().allMatch(IterateProgress::terminal)) {
            return gather(ordered, dropped);
        }
        long running = ordered.stream().filter(child -> child.status() == RunStepStatus.RUNNING).count();
        List<UUID> next = ordered.stream()
                .filter(child -> child.status() == RunStepStatus.PENDING)
                .limit(Math.max(0, effectiveConcurrency - running))
                .map(ChildState::id)
                .toList();
        return next.isEmpty() ? new Wait() : new Dispatch(next);
    }

    /** The output of every child as the step's output: see the spec for the keys; stored outputs in item order. */
    static Complete gather(List<ChildState> children, int dropped) {
        List<ChildState> ordered = inItemOrder(children);
        ArrayNode results = JsonNodeFactory.instance.arrayNode();
        ArrayNode files = JsonNodeFactory.instance.arrayNode();
        for (ChildState child : ordered) {
            if (child.status() == RunStepStatus.COMPLETED) {
                results.add(result(child.output()));
                files.addAll(readArray(child.outputs()));
            } else {
                results.addNull();
            }
        }
        return new Complete(output(results, ordered.size(), dropped), files.isEmpty() ? null : files.toString());
    }

    /**
     * The step's output and stored outputs with the completed {@code child}'s slot and files replaced by
     * its new attempt's and the counts recomputed from the results.
     *
     * @throws IllegalArgumentException when the child did not complete or {@code output} is not a gathered output
     */
    static Complete regather(String output, String outputs, ChildState child) {
        if (child.status() != RunStepStatus.COMPLETED) {
            throw new IllegalArgumentException("Only a completed child is regathered");
        }
        JsonNode gathered = parse(output);
        JsonNode results = gathered == null ? null : gathered.get("results");
        if (results == null || !results.isArray() || child.itemIndex() < 0 || child.itemIndex() >= results.size()) {
            throw new IllegalArgumentException("The output is not a gathered output holding item " + child.itemIndex());
        }
        ArrayNode slots = (ArrayNode) results;
        slots.set(child.itemIndex(), result(child.output()));
        ObjectNode updated = (ObjectNode) gathered;
        updated.set("texts", texts(slots));
        updated.put("succeeded", succeeded(slots));
        updated.put("failed", slots.size() - succeeded(slots));
        String prefix = "item-" + child.itemIndex() + "-";
        ArrayNode files = JsonNodeFactory.instance.arrayNode();
        for (JsonNode file : readArray(outputs)) {
            if (!file.path("name").asText("").startsWith(prefix)) {
                files.add(file);
            }
        }
        files.addAll(readArray(child.outputs()));
        List<JsonNode> sorted = new ArrayList<>();
        files.forEach(sorted::add);
        sorted.sort(Comparator.comparingInt(IterateProgress::fileItem));
        ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
        ordered.addAll(sorted);
        return new Complete(updated.toString(), ordered.isEmpty() ? null : ordered.toString());
    }

    /**
     * How long after pickup the step may run: {@code ceil(items / concurrency)} rounds of a child's
     * queue bound plus its run time, or {@code lease} for an empty list so the reaper never closes a step
     * that completes on its next advance.
     */
    static Duration deadline(int items, int concurrency, Duration childQueue, Duration childMaxRuntime,
            Duration lease) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }
        if (items == 0) {
            return lease;
        }
        long rounds = (items + concurrency - 1L) / concurrency;
        return childQueue.plus(childMaxRuntime).multipliedBy(rounds);
    }

    private static boolean terminal(ChildState child) {
        return child.status() == RunStepStatus.COMPLETED || child.status() == RunStepStatus.ERROR
                || child.status() == RunStepStatus.SKIPPED;
    }

    private static List<ChildState> inItemOrder(List<ChildState> children) {
        return children.stream().sorted(Comparator.comparingInt(ChildState::itemIndex)).toList();
    }

    private static String reason(ChildState child) {
        if (child.errorMessage() != null && !child.errorMessage().isBlank()) {
            return child.errorMessage();
        }
        return child.errorCode() == null ? "error" : child.errorCode();
    }

    private static String output(ArrayNode results, int total, int dropped) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.set("results", results);
        out.set("texts", texts(results));
        out.put("succeeded", succeeded(results));
        out.put("failed", total - succeeded(results));
        out.put("total", total);
        out.put("dropped", dropped);
        return out.toString();
    }

    private static ArrayNode texts(ArrayNode results) {
        ArrayNode texts = JsonNodeFactory.instance.arrayNode();
        results.forEach(result -> {
            if (result.isTextual()) {
                texts.add(result);
            }
        });
        return texts;
    }

    private static int succeeded(ArrayNode results) {
        int count = 0;
        for (JsonNode result : results) {
            if (!result.isNull()) {
                count++;
            }
        }
        return count;
    }

    /** A completed child's output as JSON when it parses (not {@code null}), else as text; {@code ""} when none. */
    private static JsonNode result(String output) {
        if (output == null) {
            return JsonNodeFactory.instance.textNode("");
        }
        JsonNode parsed = parse(output);
        if (parsed == null || parsed.isNull() || parsed.isMissingNode()) {
            return JsonNodeFactory.instance.textNode(output);
        }
        return parsed;
    }

    private static ArrayNode readArray(String json) {
        JsonNode parsed = json == null || json.isBlank() ? null : parse(json);
        if (parsed == null) {
            return JsonNodeFactory.instance.arrayNode();
        }
        if (!parsed.isArray()) {
            throw new IllegalArgumentException("Stored outputs are not an output list");
        }
        return (ArrayNode) parsed;
    }

    private static int fileItem(JsonNode file) {
        Matcher matcher = ITEM_FILE.matcher(file.path("name").asText(""));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static JsonNode parse(String json) {
        try {
            return json == null ? null : JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
