package com.flowcortex.drive.domain;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import com.flowcortex.workflow.api.RunResultView.Output;
import com.flowcortex.workflow.api.RunResultView.Step;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What counts as a member-facing result of a step; shared by Memory entries and suggestions. */
public final class ResultRules {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ResultRules() {
    }

    public static boolean isCompleted(Step step) {
        return "completed".equals(step.status());
    }

    /** The non-blank output of an inline step that is not a JSON object or array. */
    public static Optional<String> textResult(Step step) {
        String output = step.output();
        if (!"inline".equals(step.mode()) || output == null || output.isBlank() || isJsonContainer(output)) {
            return Optional.empty();
        }
        return Optional.of(output);
    }

    /** The image, video or audio kind of a MIME type; empty for anything else. */
    public static Optional<ContentType> mediaKind(String mime) {
        if (mime == null) {
            return Optional.empty();
        }
        if (mime.startsWith("image/")) {
            return Optional.of(ContentType.IMAGE);
        }
        if (mime.startsWith("video/")) {
            return Optional.of(ContentType.VIDEO);
        }
        return mime.startsWith("audio/") ? Optional.of(ContentType.AUDIO) : Optional.empty();
    }

    /** The step's image, video and audio outputs; of several outputs with one name only the first counts. */
    public static List<Output> mediaOutputs(Step step) {
        if (step.outputs() == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        return step.outputs().stream()
                .filter(output -> mediaKind(output.mime()).isPresent())
                .filter(output -> seen.add(output.name()))
                .toList();
    }

    /** At most {@code max} characters of {@code text}, never splitting a surrogate pair. */
    public static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    private static boolean isJsonContainer(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            return node.isObject() || node.isArray();
        } catch (JacksonException e) {
            return false;
        }
    }
}
