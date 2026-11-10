package com.flowcortex.llm.api;

/** What one JSON call on {@link LlmClient} or {@link LlmSlots} came to. */
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
        /** No answer within the time limit. */
        TIMEOUT,
        /** The provider answered with a server error, or with a body that could not be read. */
        UPSTREAM,
        /** The provider could not be reached at all. */
        UNREACHABLE,
        /** The provider refused the call for quota (429). */
        QUOTA_EXCEEDED,
        /** The provider rejected the request (a 4xx other than 429), or no key is configured. */
        REJECTED,
        /** The provider answered 200 without content the caller can use. */
        UNUSABLE
    }
}
