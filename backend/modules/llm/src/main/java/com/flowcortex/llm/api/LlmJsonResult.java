package com.flowcortex.llm.api;

/** What one {@link LlmClient#generateJson} call came to. */
public sealed interface LlmJsonResult permits LlmJsonResult.Answered, LlmJsonResult.Blocked, LlmJsonResult.Failed {

    /** {@code json} is the model's answer, already checked to parse as JSON. */
    record Answered(String json, String model, int inputTokens, int outputTokens) implements LlmJsonResult {
    }

    /** The provider refused the prompt or the answer for safety; {@code reason} is its own code. */
    record Blocked(String reason) implements LlmJsonResult {
    }

    record Failed(Kind kind) implements LlmJsonResult {
    }

    enum Kind {
        /** No answer within the read timeout. */
        TIMEOUT,
        /** The provider answered with an error, or with nothing usable. */
        UPSTREAM,
        /** The provider could not be reached at all. */
        UNREACHABLE
    }
}
