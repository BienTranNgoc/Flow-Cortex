package com.flowcortex.llm.gemini;

import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a slot running thinking {@code off} sends per model: the lowest setting that model accepts.
 * Gemini 3.x refuses a lower level than the one listed; 2.5 takes a token budget. A model not listed
 * gets no setting rather than a guess the API could answer with a 400.
 */
final class GeminiThinkingFloor {

    enum Mode { OFF, DEFAULT }

    private GeminiThinkingFloor() {
    }

    static Optional<ObjectNode> thinkingConfig(Mode mode, String model) {
        if (mode != Mode.OFF || model == null) {
            return Optional.empty();
        }
        String id = model.toLowerCase(Locale.ROOT);
        if (id.equals("gemini-3.5-flash-lite")) {
            return Optional.of(level("MINIMAL"));
        }
        if (id.equals("gemini-3.7-flash") || id.equals("gemini-3.8-flash")) {
            return Optional.of(level("LOW"));
        }
        if (id.startsWith("gemini-2.5-pro")) {
            return Optional.of(budget(128));
        }
        if (id.startsWith("gemini-2.5-flash")) {
            return Optional.of(budget(0));
        }
        return Optional.empty();
    }

    private static ObjectNode level(String level) {
        ObjectNode config = GeminiAttempts.JSON.createObjectNode();
        config.put("thinkingLevel", level);
        return config;
    }

    private static ObjectNode budget(int tokens) {
        ObjectNode config = GeminiAttempts.JSON.createObjectNode();
        config.put("thinkingBudget", tokens);
        return config;
    }
}
