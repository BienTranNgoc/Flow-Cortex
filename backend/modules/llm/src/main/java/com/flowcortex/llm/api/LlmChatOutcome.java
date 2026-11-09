package com.flowcortex.llm.api;

import java.util.List;

/** What one {@link LlmChatClient#stream} round came to. */
public sealed interface LlmChatOutcome
        permits LlmChatOutcome.Finished, LlmChatOutcome.Cancelled, LlmChatOutcome.Blocked, LlmChatOutcome.Failed {

    /** The round ended normally; {@code toolCalls} are the calls the model asked for, in order. */
    record Finished(String model, int inputTokens, int outputTokens, List<ToolCall> toolCalls)
            implements LlmChatOutcome {

        public Finished {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    record ToolCall(String name, String argsJson, String providerState) {
    }

    /** The listener cancelled; the connection was closed. */
    record Cancelled() implements LlmChatOutcome {
    }

    /** The provider refused the prompt or the answer for safety; {@code reason} is its own code. */
    record Blocked(String reason) implements LlmChatOutcome {
    }

    record Failed(LlmJsonResult.Kind kind) implements LlmChatOutcome {
    }
}
