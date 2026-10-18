package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.application.provider.GeminiException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The segment list the dub steps hand on — {@code {"segments":[{index,startMs,endMs,zh,vi?,edited?}]}},
 * or that list alone — and what the steps share about it: each line's character limit, the answer
 * format they ask the provider for, and the failures they end with.
 *
 * <p>Nothing here puts segment text into an exception message: those messages reach the run's
 * error and the logs.
 */
final class DubSegments {

    /** What a translation model answers with: one {@code {index, vi}} entry per line. */
    static final String LINES_SCHEMA = "{\"type\":\"ARRAY\",\"items\":{\"type\":\"OBJECT\",\"properties\":"
            + "{\"index\":{\"type\":\"INTEGER\"},\"vi\":{\"type\":\"STRING\"}},\"required\":[\"index\",\"vi\"]}}";

    /** {@code vi} is the Vietnamese line, empty when the member emptied it; {@code edited} is set by the review. */
    record Segment(int index, long startMs, long endMs, String zh, String vi, boolean edited) {

        long slotMs() {
            return endMs - startMs;
        }
    }

    private DubSegments() {
    }

    /** @throws StepFailedException {@code contract_violation} when {@code input} is not a segment list */
    static List<Segment> read(ObjectMapper json, String input) {
        JsonNode root;
        try {
            root = input == null ? null : json.readTree(input);
        } catch (JsonProcessingException e) {
            throw contractViolation("Step input is not JSON");
        }
        JsonNode list = root != null && root.isArray() ? root : root == null ? null : root.get("segments");
        if (list == null || !list.isArray()) {
            throw contractViolation("Step input is not a segment list");
        }
        List<Segment> segments = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        for (JsonNode node : list) {
            if (!node.path("index").isIntegralNumber() || !node.path("startMs").isIntegralNumber()
                    || !node.path("endMs").isIntegralNumber()
                    || node.path("endMs").asLong() <= node.path("startMs").asLong()) {
                throw contractViolation("Segment " + segments.size() + " has no index or no valid time range");
            }
            if (!indexes.add(node.path("index").asInt())) {
                throw contractViolation("Segment index " + node.path("index").asInt() + " appears more than once");
            }
            String vi = text(node.get("vi"));
            segments.add(new Segment(node.path("index").asInt(), node.path("startMs").asLong(),
                    node.path("endMs").asLong(), text(node.get("zh")), vi == null ? null : composed(vi),
                    node.path("edited").asBoolean(false)));
        }
        return segments;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** Vietnamese may arrive with its marks as separate code points; counted and voiced, a letter is one. */
    private static String composed(String line) {
        return Normalizer.normalize(line, Normalizer.Form.NFC);
    }

    /** The most characters a line may have to be read within its slot at {@code charsPerSecond}. */
    static int limit(double charsPerSecond, Segment segment) {
        return (int) Math.floor(charsPerSecond * segment.slotMs() / 1000.0);
    }

    static int length(String line) {
        String composed = composed(line);
        return composed.codePointCount(0, composed.length());
    }

    /** A provider answer as index → line, or {@code null} when it is not a list of distinct {@code {index, vi}} entries. */
    static Map<Integer, String> lines(ObjectMapper json, String answer) {
        JsonNode list;
        try {
            list = answer == null ? null : json.readTree(answer);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (list == null || !list.isArray()) {
            return null;
        }
        Map<Integer, String> lines = new LinkedHashMap<>();
        for (JsonNode entry : list) {
            if (!entry.path("index").isIntegralNumber() || !entry.path("vi").isTextual()
                    || lines.putIfAbsent(entry.path("index").asInt(), composed(entry.path("vi").asText())) != null) {
                return null;
            }
        }
        return lines;
    }

    static StepFailedException contractViolation(String message) {
        return new StepFailedException("contract_violation", message);
    }

    static StepFailedException dubDisabled() {
        return new StepFailedException("dub_disabled",
                "Vietnamese dubbing was switched off while this video was being processed. Start again later.");
    }

    static StepFailedException providerFailure(GeminiException e) {
        String message = "provider_quota_exceeded".equals(e.code())
                ? "Today's free AI quota is used up. Try again later."
                : "The AI service isn't answering right now. Try again in a few minutes.";
        return new StepFailedException(e.code(), message);
    }
}
