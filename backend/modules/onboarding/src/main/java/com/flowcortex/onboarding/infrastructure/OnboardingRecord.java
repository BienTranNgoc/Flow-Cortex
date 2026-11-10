package com.flowcortex.onboarding.infrastructure;

import com.flowcortex.onboarding.domain.StoredAnswers;
import java.time.Instant;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** One stored row of a member's onboarding; the answers are null while unanswered or skipped. */
public record OnboardingRecord(
        StoredAnswers answers,
        Instant completedAt,
        ObjectNode progress,
        ObjectNode builderState,
        Instant builderCompletedAt) {

    /** What a member with no row reads as. */
    public static OnboardingRecord empty() {
        return new OnboardingRecord(StoredAnswers.empty(), null, JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(), null);
    }
}
