package com.flowcortex.llm.api;

/**
 * One JSON-mode call. Everything here is sent to the provider, so it must never carry a name, an
 * email, a user, session or network id. {@code responseSchemaJson} is the provider's schema object as
 * JSON text; a {@code null} system instruction or schema is left out of the call.
 */
public record LlmJsonRequest(String model, String systemInstruction, String userText, String responseSchemaJson,
        double temperature, int maxOutputTokens) {

    private static final int MAX_OUTPUT_TOKENS = 8192;

    private static final double MAX_TEMPERATURE = 2.0;

    public LlmJsonRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("userText must not be blank");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxOutputTokens must be 1-" + MAX_OUTPUT_TOKENS);
        }
        if (!(temperature >= 0 && temperature <= MAX_TEMPERATURE)) {
            throw new IllegalArgumentException("temperature must be 0-" + MAX_TEMPERATURE);
        }
    }
}
