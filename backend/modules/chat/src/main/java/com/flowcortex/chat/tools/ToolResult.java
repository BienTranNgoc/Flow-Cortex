package com.flowcortex.chat.tools;

/** What a tool hands back to the model, as JSON text, and whether the call put a confirm card in the thread. */
public record ToolResult(String json, boolean cardProposed) {
}
