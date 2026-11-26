package com.flowcortex.llm.api;

/**
 * One JSON-mode call on a slot. Everything here is sent to the provider, so it must never carry a
 * name, an email, a user, session or network id. {@code responseSchemaJson} is the provider's schema
 * object as JSON text; a {@code null} system instruction, schema or temperature is left out of the
 * call. The slot's models, their output ceiling and their safeguards are the llm module's to choose.
 */
public record LlmSlotRequest(String slot, String systemInstruction, String userText, String responseSchemaJson,
        Double temperature) {

    private static final double MAX_TEMPERATURE = 2.0;

    public LlmSlotRequest {
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("slot must not be blank");
        }
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("userText must not be blank");
        }
        if (temperature != null && !(temperature >= 0 && temperature <= MAX_TEMPERATURE)) {
            throw new IllegalArgumentException("temperature must be 0-" + MAX_TEMPERATURE);
        }
    }
}
