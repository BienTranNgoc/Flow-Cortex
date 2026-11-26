package com.flowcortex.llm.gemini;

import java.util.List;
import java.util.Set;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code safetySettings} of a JSON request: the four adjustable harm categories at one threshold.
 * {@code HARM_BLOCK_THRESHOLD_UNSPECIFIED} is not accepted: it leaves the model's own default in force,
 * which a configured value is meant to replace.
 */
final class GeminiSafety {

    static final Set<String> THRESHOLDS = Set.of("BLOCK_LOW_AND_ABOVE", "BLOCK_MEDIUM_AND_ABOVE",
            "BLOCK_ONLY_HIGH", "BLOCK_NONE", "OFF");

    private static final List<String> CATEGORIES = List.of("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT");

    private GeminiSafety() {
    }

    /** @throws IllegalArgumentException naming {@code key} when {@code threshold} is not one of {@link #THRESHOLDS} */
    static void check(String key, String threshold) {
        if (threshold == null || !THRESHOLDS.contains(threshold)) {
            throw new IllegalArgumentException(key + " must be one of " + THRESHOLDS.stream().sorted().toList()
                    + ", got '" + threshold + "'");
        }
    }

    static ArrayNode settings(String threshold) {
        ArrayNode settings = GeminiAttempts.JSON.createArrayNode();
        for (String category : CATEGORIES) {
            ObjectNode entry = settings.addObject();
            entry.put("category", category);
            entry.put("threshold", threshold);
        }
        return settings;
    }
}
