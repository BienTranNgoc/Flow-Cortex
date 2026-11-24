package com.flowcortex.workflow.application;

import java.util.Optional;

/**
 * Writes the questions of a QA review. Present as a bean only when model questions are switched on;
 * with none, a review uses the standard questions.
 */
public interface QaQuestionGenerator {

    /**
     * What the model is asked about. Each text is already cut to its first 8000 characters and carries
     * no name, email or id of the member, run or workflow; {@code instruction} and {@code runInput} may be null.
     */
    record QaPrompt(String instruction, String runInput, String content) {
    }

    /** The model's answer as JSON (not yet checked), or empty when there is none to use. */
    Optional<String> questionsJson(QaPrompt prompt);
}
