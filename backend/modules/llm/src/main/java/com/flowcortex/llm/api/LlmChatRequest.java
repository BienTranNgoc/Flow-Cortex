package com.flowcortex.llm.api;

import java.util.List;

/**
 * One streamed chat round. Everything here is sent to the provider, so it must never carry a name, an
 * email, a user, session or network id. {@code language} is {@code vi} or {@code en}; anything else
 * (or null) is {@code en}. A {@code null} system instruction is left out of the call. A {@code null}
 * {@code thinkingBudget} leaves the model's own thinking default; otherwise it caps the reasoning tokens,
 * which the provider counts inside {@code maxOutputTokens}.
 */
public record LlmChatRequest(String model, String systemInstruction, List<LlmTurn> turns, List<LlmTool> tools,
        double temperature, int maxOutputTokens, String language, Integer thinkingBudget) {

    private static final int MAX_OUTPUT_TOKENS = 32768;

    private static final double MAX_TEMPERATURE = 2.0;

    public LlmChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (turns == null || turns.isEmpty()) {
            throw new IllegalArgumentException("turns must not be empty");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxOutputTokens must be 1-" + MAX_OUTPUT_TOKENS);
        }
        if (thinkingBudget != null && (thinkingBudget < 0 || thinkingBudget >= maxOutputTokens)) {
            throw new IllegalArgumentException("thinkingBudget must be 0 to below maxOutputTokens");
        }
        if (!(temperature >= 0 && temperature <= MAX_TEMPERATURE)) {
            throw new IllegalArgumentException("temperature must be 0-" + MAX_TEMPERATURE);
        }
        turns = List.copyOf(turns);
        tools = tools == null ? List.of() : List.copyOf(tools);
        language = "vi".equals(language) ? "vi" : "en";
    }

    public LlmChatRequest(String model, String systemInstruction, List<LlmTurn> turns, List<LlmTool> tools,
            double temperature, int maxOutputTokens, String language) {
        this(model, systemInstruction, turns, tools, temperature, maxOutputTokens, language, null);
    }
}
