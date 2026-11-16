package com.flowcortex.llm.api;

/** What one {@link LlmSlots#speak} call came to. */
public sealed interface LlmSpeechResult permits LlmSpeechResult.Spoken, LlmSpeechResult.Failed {

    /** One voice file (RIFF WAV) and the model that voiced it. */
    record Spoken(byte[] wav, String model) implements LlmSpeechResult {
    }

    record Failed(LlmJsonResult.Kind kind) implements LlmSpeechResult {
    }
}
