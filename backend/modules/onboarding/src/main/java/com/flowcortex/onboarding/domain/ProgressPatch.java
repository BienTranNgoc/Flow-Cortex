package com.flowcortex.onboarding.domain;

import java.time.Instant;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A validated progress write. {@code step} and {@code question} are null when the request left
 * them out; {@code finishing} asks the store to stamp {@code finishedAt} once.
 */
public record ProgressPatch(String step, Integer question, boolean surveyDeferred, boolean finishing) {

    /** The version stamp every stored progress carries. */
    public static final int VERSION = 2;

    /** The jsonb object merged over the stored progress: {@code v}, the given fields and {@code updatedAt}. */
    public ObjectNode toJson(Instant now) {
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        patch.put("v", VERSION);
        if (step != null) {
            patch.put("step", step);
        }
        if (question != null) {
            patch.put("question", question.intValue());
        }
        if (surveyDeferred) {
            patch.put("surveyDeferredAt", now.toString());
        }
        patch.put("updatedAt", now.toString());
        return patch;
    }
}
