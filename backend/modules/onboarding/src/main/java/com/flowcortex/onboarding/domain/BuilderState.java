package com.flowcortex.onboarding.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Rules for the member's builder onboarding state: a shallow-merged, size-capped JSON object. */
public final class BuilderState {

    /** Upper bound on the serialized state, in characters. */
    public static final int MAX_STATE_CHARS = 8_192;

    private static final String NOT_OBJECT = "onboarding.builder.state.notObject";
    private static final String TOO_LARGE = "onboarding.builder.state.tooLarge";
    private static final String UNKNOWN_REASON = "onboarding.builder.reason.unknown";

    private BuilderState() {
    }

    /**
     * Returns {@code stored} with the keys of {@code incoming} laid over it.
     *
     * @throws InvalidOnboardingRequest naming {@code state} when incoming is not an object or the result is over the cap
     */
    public static ObjectNode merge(ObjectNode stored, JsonNode incoming) {
        if (incoming == null || !incoming.isObject()) {
            throw new InvalidOnboardingRequest("state", NOT_OBJECT);
        }
        ObjectNode merged = stored.deepCopy();
        merged.setAll((ObjectNode) incoming);
        return capped(merged);
    }

    /**
     * Reads the body's optional {@code reason}; absent or null yields {@code null}.
     *
     * @throws InvalidOnboardingRequest naming {@code reason} when it is not skipped or finished
     */
    public static String reason(JsonNode body) {
        JsonNode reason = body == null ? null : body.get("reason");
        if (reason == null || reason.isNull()) {
            return null;
        }
        if (!reason.isString() || !OnboardingVocabulary.BUILDER_REASONS.contains(reason.asString())) {
            throw new InvalidOnboardingRequest("reason", UNKNOWN_REASON);
        }
        return reason.asString();
    }

    /**
     * Returns {@code state} with {@code reason} recorded; a null reason changes nothing.
     *
     * @throws InvalidOnboardingRequest naming {@code state} when the result is over the cap
     */
    public static ObjectNode withReason(ObjectNode state, String reason) {
        if (reason == null) {
            return state;
        }
        ObjectNode recorded = state.deepCopy();
        recorded.put("reason", reason);
        return capped(recorded);
    }

    private static ObjectNode capped(ObjectNode state) {
        if (state.toString().length() > MAX_STATE_CHARS) {
            throw new InvalidOnboardingRequest("state", TOO_LARGE);
        }
        return state;
    }
}
