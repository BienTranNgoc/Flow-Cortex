package com.flowcortex.llm.api;

/** Port for a streaming, tool-calling chat with the language model. */
public interface LlmChatClient {

    /**
     * Streams one model round. Text reaches {@code listener} as it arrives. When the model asks for
     * tools the outcome lists them: the caller runs them and calls again with the results appended.
     * Never throws for a provider failure; that is a {@link LlmChatOutcome.Failed}.
     */
    LlmChatOutcome stream(LlmChatRequest request, LlmChatListener listener);
}
