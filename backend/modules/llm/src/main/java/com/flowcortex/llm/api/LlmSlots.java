package com.flowcortex.llm.api;

import java.time.Duration;

/**
 * Calls the model by purpose: a slot names an ordered model list, the attempts each model gets and the
 * waits between them, all configured in the llm module. Never throws for a provider problem: an
 * outage, a quota refusal or a rejected call comes back as a {@code Failed} result.
 *
 * <p>An unknown slot throws {@link IllegalArgumentException}. An interrupt of the calling thread ends
 * the call with an {@link IllegalStateException} that carries no provider code, with the interrupt
 * flag set and no further request sent.
 */
public interface LlmSlots {

    /** Asks the slot's models for a JSON answer that follows {@code responseSchemaJson} (nullable). */
    LlmJsonResult generateJson(String slot, String userText, String responseSchemaJson);

    /** Voices {@code text} with {@code voice} in one request on the slot's voice models. */
    LlmSpeechResult speak(String slot, String text, String voice);

    /** The longest {@link #generateJson} may take: every attempt timing out, every wait taken, on every model. */
    Duration jsonWorstCase(String slot);

    /** The longest {@link #speak} may take: every attempt on the first model, one call on each later one. */
    Duration speechWorstCase(String slot);

    /** Whether a provider key is set; with none, every call fails {@code REJECTED}. */
    boolean configured();
}
