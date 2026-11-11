package com.flowcortex.llm.api;

/**
 * Receives one streamed round. {@link #cancelled()} is polled from a watcher thread as well as the
 * calling one, so it must be safe to call concurrently and must not block.
 */
public interface LlmChatListener {

    void onText(String chunk);

    /** {@code providerState} is opaque: hand it back unchanged on the matching {@link LlmTurn.FunctionCall}. */
    void onToolCall(String name, String argsJson, String providerState);

    boolean cancelled();
}
