package com.flowcortex.onboarding.domain;

import java.util.List;

/** A parsed quiz write: one three-valued answer per field plus the finish and skip flags. */
public record QuizRequest(
        Answer<List<String>> purposes,
        Answer<String> source,
        Answer<String> aiLevel,
        Answer<List<String>> industries,
        Answer<List<String>> contentTypes,
        Answer<String> usageType,
        boolean finished,
        boolean skipped) {

    /** The same flags with every answer absent. */
    QuizRequest withoutAnswers() {
        return new QuizRequest(Answer.absent(), Answer.absent(), Answer.absent(), Answer.absent(), Answer.absent(),
                Answer.absent(), finished, skipped);
    }
}
