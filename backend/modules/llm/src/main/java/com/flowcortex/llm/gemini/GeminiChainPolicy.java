package com.flowcortex.llm.gemini;

import com.flowcortex.llm.api.LlmJsonResult.Kind;

/**
 * What a model chain does after one attempt, as pure functions. JSON calls retry quota refusals after
 * the wait; speech calls move on at once because a daily quota does not come back within any wait, and
 * give every later voice model one request only.
 */
final class GeminiChainPolicy {

    enum Mode {
        JSON,
        SPEECH
    }

    enum Verdict {
        RETRY_AFTER_WAIT,
        NEXT_MODEL,
        STOP
    }

    private GeminiChainPolicy() {
    }

    /** The attempts model number {@code modelIndex} gets. */
    static int attemptsFor(Mode mode, int modelIndex, int configuredAttempts) {
        return mode == Mode.SPEECH && modelIndex > 0 ? 1 : configuredAttempts;
    }

    /**
     * The step after attempt number {@code attempt} of {@code attempts}. {@code failure} is {@code null}
     * when the attempt ended with an answer or a safety block. An interrupted attempt never reaches here.
     */
    static Verdict next(Mode mode, int attempt, int attempts, Kind failure) {
        if (failure == null || failure == Kind.UNUSABLE) {
            return Verdict.STOP;
        }
        if (failure == Kind.REJECTED) {
            return mode == Mode.SPEECH ? Verdict.STOP : Verdict.NEXT_MODEL;
        }
        if (failure == Kind.QUOTA_EXCEEDED && mode == Mode.SPEECH) {
            return Verdict.NEXT_MODEL;
        }
        return attempt < attempts ? Verdict.RETRY_AFTER_WAIT : Verdict.NEXT_MODEL;
    }

    /**
     * How a call that gave up is reported. An answer without usable content stays as it is; otherwise
     * a spent quota outranks any later outage, since it is what the caller can act on.
     */
    static Kind finalKind(Kind last, boolean quotaRemembered) {
        if (last == Kind.UNUSABLE) {
            return Kind.UNUSABLE;
        }
        return quotaRemembered ? Kind.QUOTA_EXCEEDED : last;
    }
}
