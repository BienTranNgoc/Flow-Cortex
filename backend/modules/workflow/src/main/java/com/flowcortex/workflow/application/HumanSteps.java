package com.flowcortex.workflow.application;

import java.util.Set;

/**
 * The step types a person decides. Neither has a {@link StepExecutor}: the advancer pauses the run
 * at them and a review task carries the decision back.
 */
final class HumanSteps {

    static final String APPROVAL_CHECKPOINT = "APPROVAL_CHECKPOINT";
    static final String QA_REVIEW = "qa.review";

    static final Set<String> TYPES = Set.of(APPROVAL_CHECKPOINT, QA_REVIEW);

    private HumanSteps() {
    }

    /** The {@code review_task.review_type} of a review opened for a step of this type. */
    static String reviewType(String stepType) {
        return switch (stepType) {
            case APPROVAL_CHECKPOINT -> "APPROVAL_CHECKPOINT";
            case QA_REVIEW -> "QA_REVIEW";
            default -> throw new IllegalArgumentException("Not a human step type: " + stepType);
        };
    }
}
