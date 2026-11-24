package com.flowcortex.workflow.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Switches of the human review steps, read once at startup. */
@Component
class ReviewSettings {

    private final Duration expireAfter;
    private final boolean aiQuestionsEnabled;

    ReviewSettings(@Value("${flowcortex.workflow.review.expire-after:7d}") Duration expireAfter,
            @Value("${flowcortex.workflow.qa-review.ai-questions.enabled:false}") boolean aiQuestionsEnabled) {
        if (expireAfter.isZero() || expireAfter.isNegative()) {
            throw new IllegalStateException("flowcortex.workflow.review.expire-after must be positive: " + expireAfter);
        }
        this.expireAfter = expireAfter;
        this.aiQuestionsEnabled = aiQuestionsEnabled;
    }

    /** How long a review waits for a decision before the sweep ends it. */
    Duration expireAfter() {
        return expireAfter;
    }

    /** Whether a QA review's questions are written by the model; off sends no run content to a provider. */
    boolean aiQuestionsEnabled() {
        return aiQuestionsEnabled;
    }
}
